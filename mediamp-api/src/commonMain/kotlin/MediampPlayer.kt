/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(MediampInternalApi::class)

package org.openani.mediamp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.playerFeaturesOf
import org.openani.mediamp.internal.MediampInternalApi
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.Chapter
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.metadata.TrackGroup
import org.openani.mediamp.metadata.VideoProperties
import org.openani.mediamp.metadata.emptyTrackGroup
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.MediaSource
import org.openani.mediamp.source.UriMediaSource
import org.openani.mediamp.source.VideoData
import org.openani.mediamp.source.VideoSourceOpenException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * An extensible media player that plays [MediaSource]s. Instances can be obtained from a [MediampPlayerFactory].
 *
 * The [MediampPlayer] interface itself defines only the minimal API for controlling the player, including:
 * - Playback State: [playbackState], [videoData], [videoProperties], [currentPositionMillis], [playbackProgress]
 * - Playback Control: [pause], [resume], [stop], [seekTo], [skip]
 *
 * Depending on whether the underlying player implementation supports a feature, [features] can be used to access them.
 *
 * ## Additional Features
 *
 * - [org.openani.mediamp.features.AudioLevelController]: Controls the audio volume and mute state.
 * - [org.openani.mediamp.features.Buffering]: Monitors the buffering progress.
 * - [org.openani.mediamp.features.PlaybackSpeed]: Controls the playback speed.
 * - [org.openani.mediamp.features.Screenshots]: Captures screenshots of the video.
 *
 * To obtain a feature, use the [PlayerFeatures.get] on [features].
 *
 * ## Threading Model
 *
 * This interface is not thread-safe. Concurrent calls to [resume] will lead to undefined behavior.
 * However, flows might be collected from multiple threads simultaneously while performing another call like [resume] on a single thread.
 *
 * All functions in this interface are expected to be called from the **main thread** on Android.
 * Calls from illegal threads will cause an exception.
 *
 * On other platforms, calls are not required to be on the main thread but should still be called from a single thread.
 * The implementation is guaranteed to be non-blocking and fast so, it is a recommended approach of making all calls from the main thread in common code.
 */
public interface MediampPlayer {
    /**
     * The underlying player implementation.
     * It can be cast to the actual player implementation to access additional features that are not yet ported by Mediamp.
     */
    public val impl: Any

    /**
     * A hot flow of the current playback state. Collect on this flow to receive state updates.
     *
     * States might be changed either by user interaction ([resume]) or by the player itself (e.g. decoder errors).
     */
    public val playbackState: StateFlow<PlaybackState>

    /**
     * The video data of the currently playing video.
     */
    public val videoData: Flow<VideoData?>

    /**
     * Sets the video source to play, by [opening][MediaSource.open] the [source],
     * updating [videoData], and calling the underlying player implementation to start playing.
     *
     * If this function failed to [start video streaming][MediaSource.open], it will throw an exception.
     *
     * This function must not be called on the main thread as it will call [MediaSource.open].
     *
     * @param source the media source to play.
     * @throws VideoSourceOpenException when failed to open the video source.
     *
     * @see stop
     */ // TODO: 2024/12/22 mention cancellation support, thread safety, errors
    @Throws(VideoSourceOpenException::class, CancellationException::class)
    public suspend fun setVideoSource(source: MediaSource<*>)

    /**
     * Properties of the video being played.
     *
     * Note that it may not be available immediately after [setVideoSource] returns,
     * since the properties may be callback from the underlying player implementation.
     */
    public val videoProperties: StateFlow<VideoProperties?>

    /**
     * Current playback position of the video being played in millis seconds, ranged from `0` to [VideoProperties.durationMillis].
     *
     * `0` if no video is being played ([videoData] is null).
     */
    public val currentPositionMillis: StateFlow<Long>

    /**
     * Obtains the exact current playback position of the video in milliseconds.
     */
    public fun getExactCurrentPositionMillis(): Long


    /**
     * A cold flow of the current playback progress, ranged from `0.0` to `1.0`.
     *
     * There is no guarantee on the frequency of updates, but it should normally be updated at once per second.
     */
    public val playbackProgress: Flow<Float>

    /**
     * Resumes playback.
     *
     * If there is no video source set, this function will do nothing.
     * @see togglePause
     */
    public fun resume()

    /**
     * Pauses playback.
     *
     * If there is no video source set, this function will do nothing.
     * @see togglePause
     */
    public fun pause()

    /**
     * Stops playback, releasing all resources and setting [videoData] to `null`.
     * Subsequent calls to [resume] will do nothing.
     *
     * To play again, call [setVideoSource].
     */
    public fun stop()

    /**
     * Jumps playback to the specified position.
     *
     * // TODO argument errors?
     */
    public fun seekTo(positionMillis: Long)

    /**
     * Skips the current playback position by [deltaMillis].
     * Positive [deltaMillis] will skip forward, and negative [deltaMillis] will skip backward.
     *
     * If the player is paused, it will remain paused, but it is guaranteed that the new frame will be displayed.
     * If there is no video source set, this function will do nothing.
     *
     * // TODO argument errors?
     */
    public fun skip(deltaMillis: Long) {
        seekTo(currentPositionMillis.value + deltaMillis)
    }

    // TODO: 2024/12/22 extract to feature 
    public val subtitleTracks: TrackGroup<SubtitleTrack>
    public val audioTracks: TrackGroup<AudioTrack>
    public val chapters: StateFlow<List<Chapter>>

    /**
     * Additional features that are supported by the underlying player implementation.
     */
    public val features: PlayerFeatures
}

/**
 * Plays the video at the specified [uri], e.g. a local file or a remote URL.
 */
public suspend fun MediampPlayer.playUri(uri: String): Unit =
    setVideoSource(UriMediaSource(uri, emptyMap(), MediaExtraFiles()))

/**
 * Toggles between [MediampPlayer.pause] and [MediampPlayer.resume] based on the current playback state.
 */
public fun MediampPlayer.togglePause() {
    if (playbackState.value.isPlaying) {
        pause()
    } else {
        resume()
    }
}


@MediampInternalApi
public abstract class AbstractMediampPlayer<D : AbstractMediampPlayer.Data>(
    parentCoroutineContext: CoroutineContext,
) : MediampPlayer {
    protected val backgroundScope: CoroutineScope = CoroutineScope(
        parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job]),
    ).apply {
        coroutineContext.job.invokeOnCompletion {
            close()
        }
    }

    override val playbackState: MutableStateFlow<PlaybackState> = MutableStateFlow(PlaybackState.PAUSED_BUFFERING)

    /**
     * Currently playing resource that should be closed when the controller is closed.
     * @see setVideoSource
     */
    protected val openResource: MutableStateFlow<D?> = MutableStateFlow<D?>(null)

    public open class Data(
        public open val mediaSource: MediaSource<*>,
        public open val videoData: VideoData,
        public open val releaseResource: () -> Unit,
    )

    final override val videoData: Flow<VideoData?> = openResource.map {
        it?.videoData
    }

    final override val playbackProgress: Flow<Float>
        get() = combine(videoProperties.filterNotNull(), currentPositionMillis) { properties, duration ->
            if (properties.durationMillis == 0L) {
                return@combine 0f
            }
            (duration / properties.durationMillis).toFloat().coerceIn(0f, 1f)
        }

    final override suspend fun setVideoSource(source: MediaSource<*>) {
        val previousResource = openResource.value
        if (source == previousResource?.mediaSource) {
            return
        }

        openResource.value = null
        previousResource?.releaseResource?.invoke()

        val opened = try {
            openSource(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw e
        }

        try {
            playbackState.value = PlaybackState.PAUSED_BUFFERING
            startPlayer(opened)
        } catch (e: CancellationException) {
            opened.releaseResource()
            throw e
        } catch (e: Throwable) {
            opened.releaseResource()
            throw e
        }

        this.openResource.value = opened
    }


    public fun closeVideoSource() {
        // TODO: 2024/12/16 proper synchronization?
        val value = openResource.value
        openResource.value = null
        value?.releaseResource?.invoke()
    }

    final override fun stop() {
        stopImpl()
        closeVideoSource()
    }

    protected abstract fun stopImpl()

    /**
     * 开始播放
     */
    protected abstract suspend fun startPlayer(data: D)

    /**
     * 停止播放, 因为要释放资源了
     */
    protected abstract suspend fun cleanupPlayer()

    @Throws(VideoSourceOpenException::class, CancellationException::class)
    protected abstract suspend fun openSource(source: MediaSource<*>): D

    private val closed = MutableStateFlow(false)
    public fun close() {
        if (closed.getAndUpdate { true }) return // already closed
        closeImpl()
        closeVideoSource()
        backgroundScope.cancel()
    }

    protected abstract fun closeImpl()
}


public enum class PlaybackState(
    public val isPlaying: Boolean,
) {
    /**
     * Player is loaded and will be playing as soon as metadata and first frame is available.
     */
    READY(isPlaying = false),

    /**
     * 用户主动暂停. buffer 继续充, 但是充好了也不要恢复 [PLAYING].
     */
    PAUSED(isPlaying = false),

    PLAYING(isPlaying = true),

    /**
     * 播放中但因没 buffer 就暂停了. buffer 填充后恢复 [PLAYING].
     */
    PAUSED_BUFFERING(isPlaying = false),

    FINISHED(isPlaying = false),

    ERROR(isPlaying = false),
    ;
}

/**
 * For previewing
 */
public class DummyMediampPlayer(
    // TODO: 2024/12/22 move to preview package
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
) : AbstractMediampPlayer<AbstractMediampPlayer.Data>(parentCoroutineContext) {
    override val impl: Any get() = this
    override val playbackState: MutableStateFlow<PlaybackState> = MutableStateFlow(PlaybackState.PLAYING)
    override fun stopImpl() {

    }

    override suspend fun cleanupPlayer() {
        // no-op
    }

    override suspend fun openSource(source: MediaSource<*>): Data {
        val data = source.open()
        return Data(
            source,
            data,
            releaseResource = {
                backgroundScope.launch(NonCancellable) {
                    data.close()
                }
            },
        )
    }

    override fun closeImpl() {
    }

    override suspend fun startPlayer(data: Data) {
        // no-op
    }

    override val videoProperties: MutableStateFlow<VideoProperties> = MutableStateFlow(
        VideoProperties(
            title = "Test Video",
            durationMillis = 100_000,
        ),
    )
    override val currentPositionMillis: MutableStateFlow<Long> = MutableStateFlow(10_000L)
    override fun getExactCurrentPositionMillis(): Long {
        return currentPositionMillis.value
    }

    override fun pause() {
        playbackState.value = PlaybackState.PAUSED
    }

    override fun resume() {
        playbackState.value = PlaybackState.PLAYING
    }

    override fun seekTo(positionMillis: Long) {
        this.currentPositionMillis.value = positionMillis
    }

    override val subtitleTracks: TrackGroup<SubtitleTrack> = emptyTrackGroup()
    override val audioTracks: TrackGroup<AudioTrack> = emptyTrackGroup()

    override val chapters: StateFlow<List<Chapter>> = MutableStateFlow(
        listOf(
            Chapter("chapter1", durationMillis = 90_000L, 0L),
            Chapter("chapter2", durationMillis = 5_000L, 90_000L),
        ),
    )

    override val features: PlayerFeatures = playerFeaturesOf()
}
