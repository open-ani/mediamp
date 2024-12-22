/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:kotlin.OptIn(MediampInternalApi::class)

package org.openani.mediamp

import android.content.Context
import android.net.Uri
import android.util.Pair
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.annotation.UiThread
import androidx.compose.runtime.Stable
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.TrackSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openani.mediamp.core.internal.MutableTrackGroup
import org.openani.mediamp.core.state.AbstractPlayerState
import org.openani.mediamp.core.state.MediaPlayerAudioController
import org.openani.mediamp.core.state.PlaybackState
import org.openani.mediamp.core.state.PlayerState
import org.openani.mediamp.core.state.PlayerStateFactory
import org.openani.mediamp.media.VideoDataDataSource
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.Chapter
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.metadata.TrackLabel
import org.openani.mediamp.metadata.VideoProperties
import org.openani.mediamp.source.HttpStreamingVideoSource
import org.openani.mediamp.source.VideoData
import org.openani.mediamp.source.VideoSource
import org.openani.mediamp.source.emptyVideoData
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.seconds

class ExoPlayerStateFactory : PlayerStateFactory<Context> {
    @OptIn(UnstableApi::class)
    override fun create(context: Context, parentCoroutineContext: CoroutineContext): PlayerState =
        ExoPlayerState(context, parentCoroutineContext)
}


@OptIn(UnstableApi::class)
@kotlin.OptIn(MediampInternalApi::class)
internal class ExoPlayerState @UiThread constructor(
    context: Context,
    parentCoroutineContext: CoroutineContext,
) : AbstractPlayerState<ExoPlayerState.ExoPlayerData>(parentCoroutineContext),
    AutoCloseable {
    class ExoPlayerData(
        videoSource: VideoSource<*>,
        videoData: VideoData,
        releaseResource: () -> Unit,
        val setMedia: () -> Unit,
    ) : Data(videoSource, videoData, releaseResource)

    override suspend fun startPlayer(data: ExoPlayerData) {
        withContext(Dispatchers.Main.immediate) {
            data.setMedia()
            player.prepare()
            player.play()
        }
    }

    override suspend fun cleanupPlayer() {
        withContext(Dispatchers.Main.immediate) {
            player.stop()
            player.clearMediaItems()
        }
    }

    override suspend fun openSource(source: VideoSource<*>): ExoPlayerData {
        if (source is HttpStreamingVideoSource) {
            return ExoPlayerData(
                source,
                emptyVideoData(),
                releaseResource = {},
                setMedia = {
                    val headers = source.headers
                    val item = MediaItem.Builder().apply {
                        setUri(source.uri)
                        setSubtitleConfigurations(
                            source.extraFiles.subtitles.map {
                                MediaItem.SubtitleConfiguration.Builder(
                                    Uri.parse(it.uri),
                                ).apply {
                                    it.mimeType?.let { mimeType -> setMimeType(mimeType) }
                                    it.language?.let { language -> setLanguage(language) }
                                }.build()
                            },
                        )
                    }.build()
                    player.setMediaSource(
                        DefaultMediaSourceFactory(
                            DefaultHttpDataSource.Factory()
                                .setUserAgent(
                                    headers["User-Agent"]
                                        ?: """Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3""",
                                )
                                .setDefaultRequestProperties(headers)
                                .setConnectTimeoutMs(30_000),
                        ).createMediaSource(item),
                    )
                },
            )
        }
        val data = source.open()
        val file = withContext(Dispatchers.IO) {
            data.createInput()
        }
        val factory = ProgressiveMediaSource.Factory {
            VideoDataDataSource(data, file)
        }
        return ExoPlayerData(
            source,
            data,
            releaseResource = {
                file.close()
                backgroundScope.launch(NonCancellable) {
                    data.close()
                }
            },
            setMedia = {
                player.setMediaSource(factory.createMediaSource(MediaItem.fromUri(source.uri)))
            },
        )
    }

    private val updateVideoPropertiesTasker = MonoTasker(backgroundScope)

    val player = kotlin.run {
        ExoPlayer.Builder(context).apply {
            setTrackSelector(
                object : DefaultTrackSelector(context) {
                    override fun selectTextTrack(
                        mappedTrackInfo: MappedTrackInfo,
                        rendererFormatSupports: Array<out Array<IntArray>>,
                        params: Parameters,
                        selectedAudioLanguage: String?
                    ): Pair<ExoTrackSelection.Definition, Int>? {
                        val preferred = subtitleTracks.current.value
                            ?: return super.selectTextTrack(
                                mappedTrackInfo,
                                rendererFormatSupports,
                                params,
                                selectedAudioLanguage,
                            )

                        infix fun SubtitleTrack.matches(group: TrackGroup): Boolean {
                            if (this.internalId == group.id) return true

                            if (this.labels.isEmpty()) return false
                            for (index in 0 until group.length) {
                                val format = group.getFormat(index)
                                if (format.labels.isEmpty()) {
                                    continue
                                }
                                if (this.labels.any { it.value == format.labels.first().value }) {
                                    return true
                                }
                            }
                            return false
                        }

                        // 备注: 这个实现可能并不好, 他只是恰好能跑
                        for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                            if (C.TRACK_TYPE_TEXT != mappedTrackInfo.getRendererType(rendererIndex)) continue

                            val groups = mappedTrackInfo.getTrackGroups(rendererIndex)
                            for (groupIndex in 0 until groups.length) {
                                val trackGroup = groups[groupIndex]
                                if (preferred matches trackGroup) {
                                    return Pair(
                                        ExoTrackSelection.Definition(
                                            trackGroup,
                                            IntArray(trackGroup.length) { it }, // 如果选择所有字幕会闪烁
                                            TrackSelection.TYPE_UNSET,
                                        ),
                                        rendererIndex,
                                    )
                                }
                            }
                        }
                        return super.selectTextTrack(
                            mappedTrackInfo,
                            rendererFormatSupports,
                            params,
                            selectedAudioLanguage,
                        )
                    }
                },
            )
        }.build().apply {
            playWhenReady = true
            addListener(
                object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        this@ExoPlayerState.playbackState.value = PlaybackState.READY
                        isBuffering.value = false
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        val newSubtitleTracks =
                            tracks.groups.asSequence()
                                .filter { it.type == C.TRACK_TYPE_TEXT }
                                .flatMapIndexed { groupIndex: Int, group: Tracks.Group ->
                                    group.getSubtitleTracks()
                                }
                                .toList()
                        // 新的字幕轨道和原来不同时才会更改，同时将 current 设置为新字幕轨道列表的第一个
                        if (newSubtitleTracks != subtitleTracks.candidates.value) {
                            subtitleTracks.candidates.value = newSubtitleTracks
                            subtitleTracks.current.value = newSubtitleTracks.firstOrNull()
                        }

                        audioTracks.candidates.value =
                            tracks.groups.asSequence()
                                .filter { it.type == C.TRACK_TYPE_AUDIO }
                                .flatMapIndexed { groupIndex: Int, group: Tracks.Group ->
                                    group.getAudioTracks()
                                }
                                .toList()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        this@ExoPlayerState.playbackState.value = PlaybackState.ERROR
                        println("ExoPlayer error: ${error.errorCodeName}") // TODO: 2024/12/16 error handling
                        error.printStackTrace()
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        super.onVideoSizeChanged(videoSize)
                        updateVideoProperties()
                    }

                    @MainThread
                    private fun updateVideoProperties(): Boolean {
                        val video = videoFormat ?: return false
                        val audio = audioFormat ?: return false
                        val data = openResource.value?.videoData ?: return false
                        val title = mediaMetadata.title
                        val duration = duration

                        // 注意, 要把所有 UI 属性全都读出来然后 captured 到 background -- ExoPlayer 所有属性都需要在主线程

                        updateVideoPropertiesTasker.launch(Dispatchers.IO) {
                            // This is in background
                            videoProperties.value = VideoProperties(
                                title = title?.toString(),
                                durationMillis = duration,
                            )
                        }
                        return true
                    }

                    /**
                     * STATE_READY 会在当前帧缓冲结束时设置
                     *
                     * exoplayer 的 STATE_READY 是不符合 [PlaybackState.READY] 预期的，所以不能在这里设置
                     *
                     * [PlaybackState.READY] 会在 [onMediaItemTransition] 中设置
                     */
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                isBuffering.value = true
                            }

                            Player.STATE_ENDED -> {
                                this@ExoPlayerState.playbackState.value = PlaybackState.FINISHED
                                isBuffering.value = false
                            }

                            Player.STATE_READY -> {
                                isBuffering.value = false
                            }
                        }
                        updateVideoProperties()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            this@ExoPlayerState.playbackState.value = PlaybackState.PLAYING
                            isBuffering.value = false
                        } else {
                            this@ExoPlayerState.playbackState.value = PlaybackState.PAUSED
                        }
                    }

                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                        super.onPlaybackParametersChanged(playbackParameters)
                        playbackSpeed.value = playbackParameters.speed
                    }
                },
            )
        }
    }

    private fun Tracks.Group.getSubtitleTracks() = sequence {
        repeat(length) { index ->
            val format = getTrackFormat(index)
            val firstLabel = format.labels.firstNotNullOfOrNull { it.value }
            format.metadata
            this.yield(
                SubtitleTrack(
                    "${openResource.value?.videoData?.filename}-${mediaTrackGroup.id}-$index",
                    mediaTrackGroup.id,
                    firstLabel ?: mediaTrackGroup.id,
                    format.labels.map { TrackLabel(it.language, it.value) },
                ),
            )
        }
    }

    private fun Tracks.Group.getAudioTracks() = sequence {
        repeat(length) { index ->
            val format = getTrackFormat(index)
            val firstLabel = format.labels.firstNotNullOfOrNull { it.value }
            format.metadata
            this.yield(
                AudioTrack(
                    "${openResource.value?.videoData?.filename}-${mediaTrackGroup.id}-$index",
                    mediaTrackGroup.id,
                    firstLabel ?: mediaTrackGroup.id,
                    format.labels.map { TrackLabel(it.language, it.value) },
                ),
            )
        }
    }

    override val isBuffering: MutableStateFlow<Boolean> = MutableStateFlow(false) // 需要单独状态, 因为要用户可能会覆盖 [state] 
    override fun stopImpl() {
        player.stop()
    }

    override val videoProperties = MutableStateFlow<VideoProperties?>(null)
    override val bufferedPercentage = MutableStateFlow(0)

    override fun seekTo(positionMillis: Long) {
        currentPositionMillis.value = positionMillis
        player.seekTo(positionMillis)
    }

    override val subtitleTracks: MutableTrackGroup<SubtitleTrack> = MutableTrackGroup()
    override val audioTracks: MutableTrackGroup<AudioTrack> = MutableTrackGroup()
    override val audioController: MediaPlayerAudioController?
        get() = TODO("Not yet implemented")

    override fun saveScreenshotFile(filename: String) {
        TODO("Not yet implemented")
    }

    override val chapters: StateFlow<List<Chapter>> = MutableStateFlow(listOf())

    override val currentPositionMillis: MutableStateFlow<Long> = MutableStateFlow(0)
    override fun getExactCurrentPositionMillis(): Long = player.currentPosition

    init {
        backgroundScope.launch(Dispatchers.Main) {
            while (currentCoroutineContext().isActive) {
                currentPositionMillis.value = player.currentPosition
                bufferedPercentage.value = player.bufferedPercentage
                delay(0.1.seconds) // 10 fps
            }
        }
        backgroundScope.launch(Dispatchers.Main) {
            subtitleTracks.current.collect {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                    setPreferredTextLanguage(it?.internalId) // dummy value to trigger a select, we have custom selector
                    setTrackTypeDisabled(C.TRACK_TYPE_TEXT, it == null) // disable subtitle track
                }.build()
            }
        }
    }

    override fun pause() {
        player.playWhenReady = false
        player.pause()
    }

    override fun resume() {
        player.playWhenReady = true
        player.play()
    }

    override val playbackSpeed: MutableStateFlow<Float> = MutableStateFlow(1f)

    override fun closeImpl() {
        @kotlin.OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.Main + NonCancellable) {
            try {
                player.stop()
                player.release()
            } catch (e: Throwable) {
                e.printStackTrace() // TODO: 2024/12/16
            }
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }
}

@Stable
private interface MonoTasker {
    val isRunning: StateFlow<Boolean>

    fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ): Job

    fun <R> async(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> R,
    ): Deferred<R>

    /**
     * 等待上一个任务完成后再执行
     */
    fun launchNext(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    )

    fun cancel(cause: CancellationException? = null)

    suspend fun cancelAndJoin()

    suspend fun join()
}

private fun MonoTasker(
    scope: CoroutineScope
): MonoTasker = object : MonoTasker {
    var job: Job? = null

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    override fun launch(
        context: CoroutineContext,
        start: CoroutineStart,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        job?.cancel()
        val newJob = scope.launch(context, start, block).apply {
            invokeOnCompletion {
                if (job === this) {
                    _isRunning.value = false
                }
            }
        }.also { job = it }
        _isRunning.value = true

        return newJob
    }

    override fun <R> async(
        context: CoroutineContext,
        start: CoroutineStart,
        block: suspend CoroutineScope.() -> R
    ): Deferred<R> {
        job?.cancel()
        val deferred = scope.async(context, start, block).apply {
            invokeOnCompletion {
                if (job === this) {
                    _isRunning.value = false
                }
            }
        }
        job = deferred
        _isRunning.value = true
        return deferred
    }

    override fun launchNext(
        context: CoroutineContext,
        start: CoroutineStart,
        block: suspend CoroutineScope.() -> Unit
    ) {
        val existingJob = job
        job = scope.launch(context, start) {
            try {
                existingJob?.join()
                block()
            } catch (e: CancellationException) {
                existingJob?.cancel()
                throw e
            }
        }.apply {
            invokeOnCompletion {
                if (job === this) {
                    _isRunning.value = false
                }
            }
        }
        _isRunning.value = true
    }

    override fun cancel(cause: CancellationException?) {
        job?.cancel(cause) // use completion handler to set _isRunning to false
    }

    override suspend fun cancelAndJoin() {
        job?.run {
            join()
        }
    }

    override suspend fun join() {
        job?.join()
    }
}

