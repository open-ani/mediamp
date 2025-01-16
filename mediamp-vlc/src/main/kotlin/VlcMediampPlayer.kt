/*
 * Copyright (C) 2024 OpenAni and contributors.
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
import kotlinx.coroutines.flow.collectLatest
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
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.Screenshots
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
public class VlcMediampPlayer(parentCoroutineContext: CoroutineContext) :
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
        MediaPlayerFactory("-v")
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

    private val buffering = VlcBuffering(player, currentPositionMillis, playbackState)
    private val setTimeLock = ReentrantLock()

    init {
        backgroundScope.launch {
            playbackState.collect {
                surface.enableRendering.value = it == PlaybackState.PLAYING
            }
        }
    }

    public class VlcjData(
        override val mediaData: MediaData,
        public val setPlay: () -> Unit,
        releaseResource: () -> Unit
    ) : Data(mediaData, releaseResource)

    private val screenshots = VlcScreenshots(player)
    private val playbackSpeed = VlcPlaybackSpeed(player)
    private val audioLevelController = VlcAudioLevelController(player)
    private val mediaMetadata = VlcMediaMetadata()

    @OptIn(ExperimentalMediampApi::class)
    override val features: PlayerFeatures = buildPlayerFeatures {
        add(Screenshots.Key, screenshots)
        add(Buffering.Key, buffering)
        add(AudioLevelController.Key, audioLevelController)
        add(PlaybackSpeed.Key, playbackSpeed)
        add(MediaMetadata, mediaMetadata)
    }

    init {
        // NOTE: must not call native player in a event
        player.events().addMediaEventListener(
            object : MediaEventAdapter() {
                override fun mediaParsedChanged(media: Media, newStatus: MediaParsedStatus) {
                    if (newStatus == MediaParsedStatus.DONE) {
                        createVideoProperties()?.let {
                            mediaProperties.value = it
                        }
                        playbackState.value = PlaybackState.READY
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

                //            override fun buffering(mediaPlayer: MediaPlayer?, newCache: Float) {
//                if (newCache != 1f) {
//                    state.value = PlaybackState.PAUSED_BUFFERING
//                } else {
//                    state.value = PlaybackState.READY
//                }
//            }

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
                    playbackState.value = PlaybackState.PLAYING
                    player.submit { player.media().parsing().parse() }

                    reloadSubtitleTracks()

                    reloadAudioTracks()
                }

                override fun paused(mediaPlayer: MediaPlayer) {
                    playbackState.value = PlaybackState.PAUSED
                }

                override fun finished(mediaPlayer: MediaPlayer) {
                    playbackState.value = PlaybackState.FINISHED
                }

                override fun stopped(mediaPlayer: MediaPlayer?) {
                    playbackState.value = PlaybackState.FINISHED
                }

                override fun error(mediaPlayer: MediaPlayer) {
                    logger.error { "vlcj player error" }
                    playbackState.value = PlaybackState.ERROR
                }

                override fun positionChanged(mediaPlayer: MediaPlayer?, newPosition: Float) {
                    val properties = mediaProperties.value
                    if (properties != null) {
                        currentPositionMillis.value = (newPosition * properties.durationMillis).toLong()
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
            openResource.filterNotNull().map { it.mediaData.extraFiles.subtitles }
                .distinctUntilChanged()
                .debounce(1000)
                .collectLatest { urls ->
                    logger.info { "Video ExtraFiles changed, updating slaves" }
                    player.media().slaves().clear()
                    for (subtitle in urls) {
                        logger.info { "Adding SUBTITLE slave: $subtitle" }
                        player.media().addSlave(MediaSlaveType.SUBTITLE, subtitle.uri, false)
                    }
                }
        }
    }

    override fun getCurrentMediaProperties(): MediaProperties? = mediaProperties.value

    override fun getCurrentPositionMillis(): Long = player.status().time()

    override fun getCurrentPlaybackState(): PlaybackState {
        return playbackState.value
    }

    override suspend fun setMediaDataImpl(data: MediaData): VlcjData = when (data) {
        is UriMediaData -> {
            VlcjData(
                data,
                setPlay = {
                    player.media().play(
                        data.uri,
                        *buildList {
                            add("http-user-agent=${data.headers["User-Agent"] ?: "Mozilla/5.0"}")
                            val referer = data.headers["Referer"]
                            if (referer != null) {
                                add("http-referrer=${referer}")
                            }
                        }.toTypedArray(),
                    )
                    lastMedia = null
                },
                releaseResource = {
                    data.close()
                },
            ).also {
                playbackState.value = PlaybackState.READY
            }
        }

        is SeekableInputMediaData -> {
            val awaitContext = SupervisorJob(backgroundScope.coroutineContext[Job.Key])
            try {
                val input = data.createInput()

                VlcjData(
                    data,
                    setPlay = {
                        val new = SeekableInputCallbackMedia(input) { awaitContext.cancel() }
                        player.controls().stop()
                        player.media().play(new)
                        lastMedia = new
                    },
                    releaseResource = {
                        logger.trace { "VLC ReleaseResource: begin" }
                        awaitContext.cancel()
                        logger.trace { "VLC ReleaseResource: close input" }
                        input.close()
                        logger.trace { "VLC ReleaseResource: close VideoData" }
                        backgroundScope.launch(NonCancellable) {
                            data.close()
                        }
                    },
                ).also {
                    playbackState.value = PlaybackState.READY
                }
            } catch (e: Throwable) {
                awaitContext.cancel(CancellationException("Failed to create input", e))
                throw e
            }
        }
    }

    override fun resumeImpl() {
        when (val state = playbackState.value) {
            PlaybackState.READY -> {
                openResource.value?.setPlay?.let { it() }

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
        @Suppress("NAME_SHADOWING")
        val positionMillis = positionMillis.coerceIn(0, mediaProperties.value?.durationMillis ?: 0)
        if (positionMillis == currentPositionMillis.value) {
            return
        }

        currentPositionMillis.value = positionMillis
        player.submit {
            setTimeLock.withLock {
                player.controls().setTime(positionMillis)
            }
        }
        surface.setAllowedDrawFrames(2) // 多渲染一帧, 防止 race 问题
    }

    override fun skip(deltaMillis: Long) {
        if (playbackState.value == PlaybackState.PAUSED) {
            // 如果是暂停, 上面 positionChanged 事件不会触发, 所以这里手动更新
            // 如果正在播放, 这里不能更新. 否则可能导致进度抖动 1 秒
            currentPositionMillis.value = (currentPositionMillis.value + deltaMillis)
                .coerceIn(0, mediaProperties.value?.durationMillis ?: 0)
        }
        player.submit {
            setTimeLock.withLock {
                player.controls().skipTime(deltaMillis) // 采用当前 player 时间
            }
        }
        surface.setAllowedDrawFrames(2) // 多渲染一帧, 防止 race 问题
    }

    override fun pauseImpl() {
        player.submit {
            player.controls().pause()
        }
    }

    override fun stopPlaybackImpl() {
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
        lastMedia?.onClose() // 在调用 VLC 之前停止阻塞线程
        lastMedia = null
        backgroundScope.launch(NonCancellable) {
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
        // 新的字幕轨道和原来不同时才会更改，同时将 current 设置为新字幕轨道列表的第一个
        if (mediaMetadata.subtitleTracks.candidates.value != newSubtitleTracks) {
            mediaMetadata.subtitleTracks.candidates.value = newSubtitleTracks
            mediaMetadata.subtitleTracks.selected.value = newSubtitleTracks.firstOrNull()
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
        private val createPlayerLock = ReentrantLock() // 如果同时加载可能会 SIGSEGV

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
    override val volume: MutableStateFlow<Float> = MutableStateFlow(1f)
    override val isMute: MutableStateFlow<Boolean> = MutableStateFlow(false)
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
    private val player: MediaPlayer,
    private val currentPositionMillis: StateFlow<Long>,
    private val playbackState: StateFlow<PlaybackState>,
) : Buffering {
    override val bufferedPercentage: MutableStateFlow<Int> = MutableStateFlow(0)
    override val isBuffering: Flow<Boolean> = flow {
        var lastPosition = currentPositionMillis.value
        while (true) {
            if (playbackState.value == PlaybackState.PLAYING) {
                emit(lastPosition == currentPositionMillis.value)
                lastPosition = currentPositionMillis.value
            }
            delay(1500)
        }
    }.distinctUntilChanged()
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
