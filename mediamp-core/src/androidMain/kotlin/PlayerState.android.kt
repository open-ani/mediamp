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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openani.mediamp.core.features.Buffering
import org.openani.mediamp.core.features.PlaybackSpeed
import org.openani.mediamp.core.features.PlayerFeatures
import org.openani.mediamp.core.features.buildPlayerFeatures
import org.openani.mediamp.core.internal.MonoTasker
import org.openani.mediamp.core.internal.MutableTrackGroup
import org.openani.mediamp.core.state.AbstractMediampPlayer
import org.openani.mediamp.core.state.PlaybackState
import org.openani.mediamp.core.state.PlayerStateFactory
import org.openani.mediamp.media.VideoDataDataSource
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.Chapter
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.metadata.TrackLabel
import org.openani.mediamp.metadata.VideoProperties
import org.openani.mediamp.source.HttpStreamingMediaSource
import org.openani.mediamp.source.MediaSource
import org.openani.mediamp.source.VideoData
import org.openani.mediamp.source.emptyVideoData
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import androidx.media3.common.Player as Media3Player

class ExoPlayerStateFactory : PlayerStateFactory<Context> {
    @OptIn(UnstableApi::class)
    override fun create(
        context: Context,
        parentCoroutineContext: CoroutineContext
    ): org.openani.mediamp.core.state.MediampPlayer =
        ExoPlayerMediampPlayer(context, parentCoroutineContext)
}


@OptIn(UnstableApi::class)
@kotlin.OptIn(MediampInternalApi::class)
internal class ExoPlayerMediampPlayer @UiThread constructor(
    context: Context,
    parentCoroutineContext: CoroutineContext,
) : AbstractMediampPlayer<ExoPlayerMediampPlayer.ExoPlayerData>(parentCoroutineContext),
    AutoCloseable {
    class ExoPlayerData(
        mediaSource: MediaSource<*>,
        videoData: VideoData,
        releaseResource: () -> Unit,
        val setMedia: () -> Unit,
    ) : Data(mediaSource, videoData, releaseResource)

    override suspend fun startPlayer(data: ExoPlayerData) {
        withContext(Dispatchers.Main.immediate) {
            data.setMedia()
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    override suspend fun cleanupPlayer() {
        withContext(Dispatchers.Main.immediate) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }
    }

    override suspend fun openSource(source: MediaSource<*>): ExoPlayerData {
        if (source is HttpStreamingMediaSource) {
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
                    exoPlayer.setMediaSource(
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
                exoPlayer.setMediaSource(factory.createMediaSource(MediaItem.fromUri(source.uri)))
            },
        )
    }

    private val updateVideoPropertiesTasker = MonoTasker(backgroundScope)

    val exoPlayer = kotlin.run {
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
                object : Media3Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        this@ExoPlayerMediampPlayer.playbackState.value = PlaybackState.READY
                        buffering.isBuffering.value = false
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
                        this@ExoPlayerMediampPlayer.playbackState.value = PlaybackState.ERROR
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
                            Media3Player.STATE_BUFFERING -> {
                                buffering.isBuffering.value = true
                            }

                            Media3Player.STATE_ENDED -> {
                                this@ExoPlayerMediampPlayer.playbackState.value = PlaybackState.FINISHED
                                buffering.isBuffering.value = false
                            }

                            Media3Player.STATE_READY -> {
                                buffering.isBuffering.value = false
                            }
                        }
                        updateVideoProperties()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            this@ExoPlayerMediampPlayer.playbackState.value = PlaybackState.PLAYING
                            buffering.isBuffering.value = false
                        } else {
                            this@ExoPlayerMediampPlayer.playbackState.value = PlaybackState.PAUSED
                        }
                    }

                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                        super.onPlaybackParametersChanged(playbackParameters)
                        playbackSpeed.valueFlow.value = playbackParameters.speed
                    }
                },
            )
        }
    }

    override val videoProperties = MutableStateFlow<VideoProperties?>(null)

    val buffering = BufferingImpl()

    inner class BufferingImpl : Buffering {
        override val isBuffering: MutableStateFlow<Boolean> = MutableStateFlow(false)
        override val bufferedPercentage = MutableStateFlow(0)
    }

    override val subtitleTracks: MutableTrackGroup<SubtitleTrack> = MutableTrackGroup()
    override val audioTracks: MutableTrackGroup<AudioTrack> = MutableTrackGroup()

    override val chapters: StateFlow<List<Chapter>> = MutableStateFlow(listOf())
    override val currentPositionMillis: MutableStateFlow<Long> = MutableStateFlow(0)

    private val playbackSpeed = PlaybackSpeedImpl()

    private inner class PlaybackSpeedImpl : PlaybackSpeed {
        override val valueFlow: MutableStateFlow<Float> = MutableStateFlow(1f)
        override val value: Float get() = playbackSpeed.value

        override fun set(speed: Float) {
            valueFlow.value = speed.coerceAtLeast(0f)
            exoPlayer.setPlaybackSpeed(speed)
        }
    }

    override val features: PlayerFeatures = buildPlayerFeatures {
        add(PlaybackSpeed, playbackSpeed)
        add(Buffering, buffering)
    }

    init {
        backgroundScope.launch(Dispatchers.Main) {
            while (currentCoroutineContext().isActive) {
                currentPositionMillis.value = exoPlayer.currentPosition
                buffering.bufferedPercentage.value = exoPlayer.bufferedPercentage
                delay(0.1.seconds) // 10 fps
            }
        }
        backgroundScope.launch(Dispatchers.Main) {
            subtitleTracks.current.collect {
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().apply {
                    setPreferredTextLanguage(it?.internalId) // dummy value to trigger a select, we have custom selector
                    setTrackTypeDisabled(C.TRACK_TYPE_TEXT, it == null) // disable subtitle track
                }.build()
            }
        }
    }

    override fun seekTo(positionMillis: Long) {
        currentPositionMillis.value = positionMillis
        exoPlayer.seekTo(positionMillis)
    }

    override fun getExactCurrentPositionMillis(): Long = exoPlayer.currentPosition

    override fun pause() {
        exoPlayer.playWhenReady = false
        exoPlayer.pause()
    }

    override fun resume() {
        exoPlayer.playWhenReady = true
        exoPlayer.play()
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

    override fun stopImpl() {
        exoPlayer.stop()
    }


    override fun closeImpl() {
        @kotlin.OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.Main + NonCancellable) {
            try {
                exoPlayer.stop()
                exoPlayer.release()
            } catch (e: Throwable) {
                e.printStackTrace() // TODO: 2024/12/16
            }
        }
    }
}
