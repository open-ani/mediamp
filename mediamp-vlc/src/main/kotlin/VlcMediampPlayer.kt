/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(InternalMediampApi::class)

package org.openani.mediamp.vlc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.openani.mediamp.AbstractMediampPlayer
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.FramePreview
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.Screenshots
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.features.buildPlayerFeatures
import org.openani.mediamp.internal.MutableTrackGroup
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.Chapter
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.metadata.TrackLabel
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.vlc.VlcMediampPlayer.VlcjData
import org.openani.mediamp.vlc.internal.VlcPlaybackStateMapper
import org.openani.mediamp.vlc.internal.VlcSeekCoordinator
import org.openani.mediamp.vlc.internal.io.SeekableInputCallbackMedia
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.media.Media
import uk.co.caprica.vlcj.media.MediaEventAdapter
import uk.co.caprica.vlcj.media.MediaParsedStatus
import uk.co.caprica.vlcj.media.MediaSlaveType
import uk.co.caprica.vlcj.media.TrackType
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.io.File
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt

@OptIn(InternalMediampApi::class, InternalForInheritanceMediampApi::class)
public class VlcMediampPlayer(
    parentCoroutineContext: CoroutineContext,
    config: VlcConfig = VlcConfig(),
) :
    MediampPlayer,
    AbstractMediampPlayer<VlcjData>(Dispatchers.Default) {

    private val backgroundScope: CoroutineScope = CoroutineScope(
        parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job.Key]),
    ).apply {
        coroutineContext.job.invokeOnCompletion {
            close()
        }
    }

    //    val mediaPlayerFactory = MediaPlayerFactory(
//        "--video-title=vlcj video output",
//        "--no-snapshot-preview",
//        "--intf=dummy",
//        "-v"
//    )

    public val player: EmbeddedMediaPlayer = createPlayerLock.withLock {
        MediaPlayerFactory(*config.toArgs().toTypedArray())
            .mediaPlayers()
            .newEmbeddedMediaPlayer()
    }

    @InternalMediampApi
    public val surface: SkiaBitmapVideoSurface = SkiaBitmapVideoSurface().apply {
        player.videoSurface().set(this) // 只能 attach 一次
        attach(player)
    }
    override val impl: EmbeddedMediaPlayer get() = player

    private var lastMedia: SeekableInputCallbackMedia? = null // keep referenced so won't be gc'ed

    override val mediaProperties: MutableStateFlow<MediaProperties?> = MutableStateFlow(null)

    override val currentPositionMillis: MutableStateFlow<Long> = MutableStateFlow(0)

    private val buffering = VlcBuffering(currentPositionMillis, playbackState)
    private val playbackStateMapper = VlcPlaybackStateMapper()
    private val setTimeLock = ReentrantLock()
    private val seekCoordinator = VlcSeekCoordinator()

    init {
        backgroundScope.launch {
            playbackState.collect {
                surface.enableRendering.value = it == PlaybackState.PLAYING
            }
        }
    }

    public open class VlcjData(
        override val mediaData: MediaData,
        private val setPlay: () -> Unit,
    ) : Data(mediaData) {
        private val playRequested = AtomicBoolean(false)

        internal fun play() {
            if (playRequested.compareAndSet(false, true)) {
                setPlay()
            }
        }
    }

    private val screenshots = VlcScreenshots(player)
    private val playbackSpeed = VlcPlaybackSpeed(player)
    private val audioLevelController = VlcAudioLevelController(player)
    private val mediaMetadata = VlcMediaMetadata()
    private val videoAspectRatio = VlcVideoAspectRatio()
    private val framePreview = VlcFramePreview { openResource.value?.mediaData }

    @OptIn(ExperimentalMediampApi::class)
    override val features: PlayerFeatures = buildPlayerFeatures {
        add(Screenshots.Key, screenshots)
        add(Buffering.Key, buffering)
        add(AudioLevelController.Key, audioLevelController)
        add(PlaybackSpeed.Key, playbackSpeed)
        add(MediaMetadata, mediaMetadata)
        add(VideoAspectRatio.Key, videoAspectRatio)
        add(FramePreview.Key, framePreview)
    }

    init {
        backgroundScope.launch {
            // Tear down the preview decoder when the media changes or playback stops,
            // so it never outlives the media data it reads from.
            mediaData.collect {
                framePreview.onMediaDataChanged(it)
            }
        }
        // NOTE: must not call native player in a event
        player.events().addMediaEventListener(
            object : MediaEventAdapter() {
                override fun mediaParsedChanged(media: Media, newStatus: MediaParsedStatus) {
                    if (playbackState.value <= PlaybackState.FINISHED) {
                        return
                    }
                    if (newStatus == MediaParsedStatus.DONE) {
                        createVideoProperties()?.let {
                            mediaProperties.value = it
                        }
                    }
                }
            },
        )
        player.events().addMediaPlayerEventListener(
            object : MediaPlayerEventAdapter() {
                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                    // 对于 m3u8, 这个 callback 会先调用
                    mediaProperties.value = mediaProperties.value?.copy(
                        durationMillis = newLength,
                    ) ?: MediaProperties(
                        title = null,
                        durationMillis = newLength, // 至少要把 length 放进去, 否则会一直显示缓冲
                    )
                }

                override fun elementaryStreamAdded(mediaPlayer: MediaPlayer?, type: TrackType?, id: Int) {
                    if (type == TrackType.TEXT) {
                        reloadSubtitleTracks() // 字幕轨道更新后，则进行重载UI上的字幕轨道
                    }
                    if (type == TrackType.AUDIO) {
                        reloadAudioTracks()
                    }
                }

                override fun buffering(mediaPlayer: MediaPlayer?, newCache: Float) {
                    buffering.bufferedPercentage.value = newCache.roundToInt().coerceIn(0, 100)
                    playbackStateMapper.onBuffering(playbackState.value, newCache)?.let {
                        playbackState.value = it
                    }
                }

                override fun mediaPlayerReady(mediaPlayer: MediaPlayer?) {
                    player.submit {
                        audioLevelController.setVolume(audioLevelController.volume.value)
                        audioLevelController.setMute(audioLevelController.isMute.value)
                    }

                    mediaMetadata.chaptersMutable.value = player.chapters().allDescriptions().flatMap { title ->
                        title.map {
                            Chapter(
                                name = it.name(),
                                durationMillis = it.duration(),
                                offsetMillis = it.offset(),
                            )
                        }
                    }
                }

                override fun playing(mediaPlayer: MediaPlayer) {
                    playbackStateMapper.onPlaying(playbackState.value)?.let {
                        playbackState.value = it
                    } ?: return
                    player.submit { player.media().parsing().parse() }

                    reloadSubtitleTracks()

                    reloadAudioTracks()
                }

                override fun paused(mediaPlayer: MediaPlayer) {
                    playbackStateMapper.onPaused(playbackState.value)?.let {
                        playbackState.value = it
                    }
                }

                override fun finished(mediaPlayer: MediaPlayer) {
                    if (playbackState.value <= PlaybackState.FINISHED) {
                        return
                    }
                    playbackStateMapper.reset()
                    playbackState.value = PlaybackState.FINISHED
                }

                override fun stopped(mediaPlayer: MediaPlayer?) {
                    // VLC emits this asynchronously for controls().stop(), including stops we issue while replacing media.
                    // stopPlaybackImpl already moves the public state to FINISHED synchronously, and natural EOF is handled
                    // by finished(). Letting a late stopped event mutate state can mark the newly-started media as FINISHED.
                }

                override fun error(mediaPlayer: MediaPlayer) {
                    if (playbackState.value <= PlaybackState.FINISHED) {
                        return
                    }
                    logger.error { "vlcj player error" }
                    playbackStateMapper.reset()
                    playbackState.value = PlaybackState.ERROR
                }

                override fun positionChanged(mediaPlayer: MediaPlayer?, newPosition: Float) {
                    val properties = mediaProperties.value
                    if (properties != null) {
                        val reported = (newPosition * properties.durationMillis).toLong()
                        // While a seek is settling, VLC still emits pre-seek positions; publishing
                        // them pulls the progress bar back (open-ani/animeko#1238).
                        val decision = seekCoordinator.onPositionReported(reported)
                        if (decision.acceptPosition) {
                            currentPositionMillis.value = reported
                        }
                        if (decision.submitTarget != VlcSeekCoordinator.NONE) {
                            submitNativeSeek(decision.submitTarget)
                        }
                    }
                }
            },
        )

        backgroundScope.launch {
            mediaMetadata.subtitleTracks.selected.collect { track ->
                try {
                    if (playbackState.value == PlaybackState.READY) {
                        return@collect
                    }
                    if (track == null) {
                        if (player.subpictures().track() != -1) {
                            player.subpictures().setTrack(-1)
                        }
                        return@collect
                    }
                    val id = track.internalId.toIntOrNull() ?: run {
                        logger.error { "Invalid subtitle track id: ${track.id}" }
                        return@collect
                    }
                    val subTrackIds = player.subpictures().trackDescriptions().map { it.id() }
                    logger.info { "All ids: $subTrackIds" }
                    if (!subTrackIds.contains(id)) {
                        logger.error { "Invalid subtitle track id: $id" }
                        return@collect
                    }
                    player.subpictures().setTrack(id)
                    logger.info { "Set subtitle track to $id (${track.labels.firstOrNull()})" }
                } catch (e: Throwable) {
                    logger.error(e) { "Exception while setting subtitle track" }
                }
            }
        }

        backgroundScope.launch {
            mediaMetadata.audioTracks.selected.collect { track ->
                try {
                    if (playbackState.value == PlaybackState.READY) {
                        return@collect
                    }
                    if (track == null) {
                        if (player.audio().track() != -1) {
                            player.audio().setTrack(-1)
                        }
                    }

                    val id = track?.internalId?.toIntOrNull() ?: run {
                        if (track != null) {
                            logger.error { "Invalid audio track id: ${track.id}" }
                        }
                        return@collect
                    }
                    val count = player.audio().trackCount()
                    if (id > count) {
                        logger.error { "Invalid audio track id: $id, count: $count" }
                        return@collect
                    }
                    logger.info { "All ids: ${player.audio().trackDescriptions().map { it.id() }}" }
                    player.audio().setTrack(id)
                    logger.info { "Set audio track to $id (${track.labels.firstOrNull()})" }
                } catch (e: Throwable) {
                    logger.error(e) { "Exception while setting audio track" }
                }
            }
        }

        backgroundScope.launch {
            playbackState.map { it >= PlaybackState.PLAYING }.combine(
                openResource.filterNotNull()
                    .map { it.mediaData.extraFiles.subtitles }
                    .distinctUntilChanged()
                    .debounce(1000),
            ) { isPlaying, subtitles ->
                if (!isPlaying) return@combine

                logger.info { "Video ExtraFiles changed, updating slaves" }
                player.media().slaves().clear()
                for (subtitle in subtitles) {
                    logger.info { "Adding SUBTITLE slave: $subtitle" }
                    player.media().addSlave(MediaSlaveType.SUBTITLE, subtitle.uri, false)
                }
            }.collect()
        }
    }

    override fun getCurrentMediaProperties(): MediaProperties? = mediaProperties.value

    override fun getCurrentPositionMillis(): Long = currentPositionMillis.value

    override fun getCurrentPlaybackState(): PlaybackState {
        return playbackState.value
    }

    @OptIn(ExperimentalMediampApi::class)
    override suspend fun setMediaDataImpl(data: MediaData): VlcjData = when (data) {
        is UriMediaData -> {
            playbackStateMapper.reset()
            seekCoordinator.reset()
            VlcjData(
                data,
                setPlay = {
                    val lowerHeaders = data.headers.mapKeys { it.key.lowercase() }
                    player.submit {
                        player.media().play(
                            data.uri,
                            *buildList {
                                add("http-user-agent=${lowerHeaders["user-agent"] ?: "Mozilla/5.0"}")
                                val referer = lowerHeaders["referer"]
                                if (referer != null) {
                                    add("http-referrer=${referer}")
                                }
                                addAll(data.options)
                            }.toTypedArray(),
                        )
                    }
                    lastMedia = null
                },
            ).also {
                playbackState.value = PlaybackState.READY
            }
        }

        is SeekableInputMediaData -> {
            playbackStateMapper.reset()
            seekCoordinator.reset()
            val awaitJob = SupervisorJob(backgroundScope.coroutineContext[Job.Key])
            try {
                val input = data.createInput(backgroundScope.coroutineContext + awaitJob)

                object : VlcjData(
                    data,
                    {
                        val new = SeekableInputCallbackMedia(input) { awaitJob.cancel() }
                        lastMedia = new
                        player.submit {
                            player.media().play(new, *data.options.toTypedArray())
                        }
                    },
                ) {
                    override fun release() {
                        logger.trace { "VLC ReleaseResource: begin" }
                        awaitJob.cancel()
                        logger.trace { "VLC ReleaseResource: close input" }
                        input.close()
                        logger.trace { "VLC ReleaseResource: close VideoData" }
                        backgroundScope.launch(NonCancellable) {
                            super.release()
                        }
                    }
                }.also {
                    playbackState.value = PlaybackState.READY
                }
            } catch (e: Throwable) {
                awaitJob.cancel(CancellationException("Failed to create input", e))
                throw e
            }
        }
    }

    override fun resumeImpl() {
        when (val state = playbackState.value) {
            PlaybackState.READY -> {
                val resource = openResource.value ?: return
                playbackStateMapper.onPlayRequested(state)
                try {
                    resource.play()
                } catch (e: Throwable) {
                    playbackStateMapper.reset()
                    playbackState.value = PlaybackState.ERROR
                    throw e
                }

                //        player.media().options().add(*arrayOf(":avcodec-hw=none")) // dxva2
                //        player.controls().play()
                //        player.media().play/*OR .start*/(data.videoData.file.absolutePath)
            }

            PlaybackState.PAUSED -> {
                player.submit {
                    player.controls().play()
                }
            }

            else -> logger.warn { "unreachable state $state in resume." }
        }
    }

    override fun seekTo(positionMillis: Long) {
        if (playbackState.value < PlaybackState.READY || openResource.value == null) {
            return
        }
        @Suppress("NAME_SHADOWING")
        val positionMillis = coercePositionMillis(positionMillis)
        if (positionMillis == currentPositionMillis.value) {
            return
        }

        // Optimistic update: the UI follows this value; stale VLC events are gated by the
        // coordinator, and skip() uses it as the base so rapid skips accumulate correctly.
        currentPositionMillis.value = positionMillis

        val submit = seekCoordinator.requestSeek(positionMillis)
        if (submit != VlcSeekCoordinator.NONE) {
            submitNativeSeek(submit)
        } else {
            // Throttled: our target got queued (latest wins). Guarantee the trailing flush
            // even if no further requests or position events arrive (e.g. while paused).
            backgroundScope.launch {
                delay(seekCoordinator.minSubmitInterval + 20)
                val flushed = seekCoordinator.flushQueued()
                if (flushed != VlcSeekCoordinator.NONE) {
                    submitNativeSeek(flushed)
                }
            }
        }
    }

    private fun submitNativeSeek(positionMillis: Long) {
        player.submit {
            setTimeLock.withLock {
                player.controls().setTime(positionMillis)
            }
        }
        surface.setAllowedDrawFrames(2) // 多渲染一帧, 防止 race 问题
    }

    override fun skip(deltaMillis: Long) {
        if (playbackState.value < PlaybackState.READY || openResource.value == null) {
            return
        }
        // Base on our own (optimistically updated) position, NOT vlcj skipTime's native clock:
        // while a previous seek is settling the native clock reports pre-seek positions, which
        // made rapid skips compute from a regressed base and get lost (open-ani/animeko#1238).
        seekTo(currentPositionMillis.value + deltaMillis)
    }

    private fun coercePositionMillis(positionMillis: Long): Long {
        val durationMillis = mediaProperties.value?.durationMillis?.takeIf { it > 0 }
        return if (durationMillis == null) {
            positionMillis.coerceAtLeast(0)
        } else {
            positionMillis.coerceIn(0, durationMillis)
        }
    }

    override fun pauseImpl() {
        player.submit {
            player.controls().pause()
        }
    }

    override fun stopPlaybackImpl() {
        playbackStateMapper.reset()
        seekCoordinator.reset()
        playbackState.value = PlaybackState.FINISHED
        currentPositionMillis.value = 0L
        currentPositionMillis.value = 0L
        lastMedia?.onClose() // Stop blocking thread before closing VLC. Otherwise vlc stop() may hang forever
        try {
            player.submit {
                player.controls().stop()
            }
        } catch (_: RejectedExecutionException) {
        }
        surface.clearBitmap()
    }

    override fun closeImpl() {
        playbackStateMapper.reset()
        playbackState.value = PlaybackState.DESTROYED
        lastMedia?.onClose() // 在调用 VLC 之前停止阻塞线程
        lastMedia = null
        backgroundScope.launch(NonCancellable) {
            framePreview.closeSuspending()
            player.release()
            backgroundScope.cancel()
        }
    }

    private fun reloadSubtitleTracks() {
        val newSubtitleTracks = player.subpictures().trackDescriptions()
            .filterNot { it.id() == -1 } // "Disable"
            .map {
                SubtitleTrack(
                    it.id().toString(),
                    it.id().toString(),
                    null,
                    listOf(TrackLabel(null, it.description())),
                )
            }
        // 只有候选列表真正变化时才更新（Track 有值相等语义，重新创建的对象不会误判为变化）。
        // 变化时按 id 恢复原选择：用户选中的轨道仍存在则保持；用户已关闭字幕则保持关闭；
        // 只有首次出现候选时才默认选择第一轨 (open-ani/animeko#1128)。
        // 本函数会在每次进入 playing（含暂停后恢复）时被调用，绝不能无条件重置选择。
        val oldSubtitleTracks = mediaMetadata.subtitleTracks.candidates.value
        if (oldSubtitleTracks != newSubtitleTracks) {
            val previousSelected = mediaMetadata.subtitleTracks.selected.value
            mediaMetadata.subtitleTracks.candidates.value = newSubtitleTracks
            mediaMetadata.subtitleTracks.selected.value = when {
                oldSubtitleTracks.isEmpty() -> newSubtitleTracks.firstOrNull()
                previousSelected == null -> null
                else -> newSubtitleTracks.firstOrNull { it.id == previousSelected.id }
                    ?: newSubtitleTracks.firstOrNull()
            }
        }
    }

    private fun reloadAudioTracks() {
        mediaMetadata.audioTracks.candidates.value = player.audio().trackDescriptions()
            .filterNot { it.id() == -1 } // "Disable"
            .map {
                AudioTrack(
                    it.id().toString(),
                    it.id().toString(),
                    null,
                    listOf(TrackLabel(null, it.description())),
                )
            }
    }

    private fun createVideoProperties(): MediaProperties? {
        val info = player.media().info() ?: return null
        val title = player.titles().titleDescriptions().firstOrNull()
        return MediaProperties(
            title = title?.name(),
            durationMillis = info.duration(),
        )
    }

    public companion object {
        internal val createPlayerLock = ReentrantLock() // 如果同时加载可能会 SIGSEGV

        public fun prepareLibraries() {
            createPlayerLock.withLock {
                NativeDiscovery().discover()
                CallbackMediaPlayerComponent().release()
            }
        }

        private val logger = Logger
    }
}


private object Logger {
    inline fun trace(message: () -> String) {
        println("INFO: ${message()}")
    }

    inline fun info(message: () -> String) {
        println("INFO: ${message()}")
    }

    inline fun warn(message: () -> String) {
        println("WARN: ${message()}")
    }

    fun warn(message: String, throwable: Throwable?) {
        println("WARN: $message")
        throwable?.printStackTrace()
    }

    inline fun warn(throwable: Throwable?, message: () -> String) {
        println("WARN: ${message()}")
        throwable?.printStackTrace()
    }

    inline fun error(message: () -> String) {
        println("ERROR: ${message()}")
    }

    inline fun error(throwable: Throwable?, message: () -> String) {
        println("ERROR: ${message()}")
        throwable?.printStackTrace()
    }
}

@OptIn(InternalForInheritanceMediampApi::class)
internal class VlcMediaMetadata : MediaMetadata {
    override val audioTracks: MutableTrackGroup<AudioTrack> = MutableTrackGroup()
    override val subtitleTracks: MutableTrackGroup<SubtitleTrack> = MutableTrackGroup()
    internal val chaptersMutable: MutableStateFlow<List<Chapter>?> = MutableStateFlow(null)
    override val chapters = chaptersMutable.filterNotNull()
}

internal class VlcScreenshots(
    private val player: MediaPlayer
) : Screenshots {
    override suspend fun takeScreenshot(destinationFile: String) {
        suspendCoroutine { cont ->
            player.submit {
//                    val ppszPath = PointerByReference()
//                    Shell32.INSTANCE.SHGetKnownFolderPath(KnownFolders.FOLDERID_Pictures, 0, null, ppszPath)
//                    val picturesPath = ppszPath.value.getWideString(0)
//                    val screenshotPath: Path = Path.of(picturesPath).resolve("Ani")
//                    try {
//                        screenshotPath.createDirectories()
//                    } catch (ex: IOException) {
//                        logger.warn("Create ani pictures dir fail", ex)
//                    }
//                    val filePath = screenshotPath.resolve(destinationFile)
                player.snapshots().save(File(destinationFile))
                cont.resume(null)
            }
        }
    }
}


@OptIn(InternalForInheritanceMediampApi::class)
internal class VlcPlaybackSpeed(
    private val player: MediaPlayer
) : PlaybackSpeed {
    override val valueFlow: MutableStateFlow<Float> = MutableStateFlow(1.0f)
    override val value: Float get() = valueFlow.value

    override fun set(speed: Float) {
        player.submit {
            player.controls().setRate(speed)
        }
        valueFlow.value = speed
    }
}

@OptIn(InternalForInheritanceMediampApi::class)
internal class VlcAudioLevelController(
    private val player: MediaPlayer
) : AudioLevelController {
    override val volume: MutableStateFlow<Float> = MutableStateFlow(player.audio().volume() / 100f)
    override val isMute: MutableStateFlow<Boolean> = MutableStateFlow(player.audio().isMute)
    override val maxVolume: Float = 2f

    override fun setMute(mute: Boolean) {
        if (player.audio().isMute == mute) {
            return
        }
        isMute.value = mute
        player.audio().mute()
    }

    override fun setVolume(volume: Float) {
        this.volume.value = volume.coerceIn(0f, maxVolume)
        player.audio().setVolume(volume.times(100).roundToInt())
    }

    override fun volumeUp(value: Float) {
        setVolume(volume.value + value)
    }

    override fun volumeDown(value: Float) {
        setVolume(volume.value - value)
    }
}

@OptIn(InternalForInheritanceMediampApi::class, ExperimentalMediampApi::class)
internal class VlcBuffering(
    private val currentPositionMillis: StateFlow<Long>,
    private val playbackState: StateFlow<PlaybackState>,
) : Buffering {
    override val bufferedPercentage: MutableStateFlow<Int> = MutableStateFlow(0)
    override val isBuffering: Flow<Boolean> = flow {
        var lastState = playbackState.value
        var lastPosition = currentPositionMillis.value
        while (true) {
            val currentState = playbackState.value
            val currentPosition = currentPositionMillis.value
            emit(
                when {
                    currentState == PlaybackState.PAUSED_BUFFERING -> true
                    currentState == PlaybackState.PLAYING && lastState == PlaybackState.PLAYING ->
                        lastPosition == currentPosition

                    else -> false
                },
            )
            lastState = currentState
            lastPosition = currentPosition
            delay(1500)
        }
    }.distinctUntilChanged()
}

@OptIn(InternalForInheritanceMediampApi::class)
internal class VlcVideoAspectRatio : VideoAspectRatio {
    override val mode: MutableStateFlow<AspectRatioMode> = MutableStateFlow(AspectRatioMode.FIT)

    override fun setMode(mode: AspectRatioMode) {
        this.mode.value = mode
    }
}


// add contract
@OptIn(ExperimentalContracts::class)
private inline fun <T> ReentrantLock.withLock(block: () -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
