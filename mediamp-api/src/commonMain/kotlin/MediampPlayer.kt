/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(InternalMediampApi::class)

package org.openani.mediamp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.buildPlayerFeatures
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.Chapter
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.metadata.TrackGroup
import org.openani.mediamp.metadata.emptyTrackGroup
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KClass

/**
 * An extensible media player that plays [MediaData]s. Instances can be obtained from a [MediampPlayerFactory].
 *
 * The [MediampPlayer] interface itself defines only the minimal API for controlling the player, including:
 * - Playback State: [playbackState], [mediaData], [mediaProperties], [currentPositionMillis], [playbackProgress]
 * - Playback Control: [pause], [resume], [stopPlayback], [seekTo], [skip]
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
 * All functions in this interface are expected to be called from the **UI thread** on Android.
 * Calls from illegal threads will cause an exception.
 *
 * On other platforms, calls are not required to be on the UI thread but should still be called from a single thread.
 * The implementation is guaranteed to be non-blocking and fast so, it is a recommended approach of making all calls from the UI thread in common code.
 *
 * ## Not safe for inheritance
 *
 * [MediampPlayer] interface is not safe for inheritance from third-party users, as new abstract methods might be added in the future.
 */
@SubclassOptInRequired(InternalForInheritanceMediampApi::class)
public interface MediampPlayer : AutoCloseable {
    /**
     * The underlying player implementation.
     * It can be cast to the actual player implementation to access additional features that are not yet ported by Mediamp.
     *
     * Refer to platform-specific inheritor of [MediampPlayer] for the actual type of this property.
     */
    public val impl: Any

    /**
     * A hot flow of the current playback state. Collect on this flow to receive state updates.
     *
     * States might be changed either by user interaction ([resume]) or by the player itself (e.g. decoder errors).
     *
     * To retrieve the current state without suspension, use [getCurrentPlaybackState].
     *
     * @see getCurrentPlaybackState
     */
    public val playbackState: StateFlow<PlaybackState>

    /**
     * The video data of the currently playing video.
     */
    public val mediaData: Flow<MediaData?>

    /**
     * Properties of the video being played.
     *
     * Note that it may not be available immediately after [setMediaData] returns,
     * since the properties may be callback from the underlying player implementation.
     *
     * To get more metadata information, e.g. audio tracks, subtitles and chapters, use [features] to get [MediaMetadata].
     * @see features
     * @see MediaMetadata
     * @see getCurrentMediaProperties
     */
    public val mediaProperties: StateFlow<MediaProperties?>

    /**
     * Gets the current media properties without suspension.
     *
     * To subscribe for updates, use [mediaProperties].
     * @see mediaProperties
     */
    public fun getCurrentMediaProperties(): MediaProperties?

    /**
     * Current playback position of the video being played in millis seconds, ranged from `0` to [MediaProperties.durationMillis].
     *
     * `0` if no video is being played ([mediaData] is null).
     *
     * To obtain the current position without suspension, use [getCurrentPositionMillis].
     *
     * @see getCurrentPositionMillis
     */
    public val currentPositionMillis: StateFlow<Long>

    /**
     * A cold flow of the current playback progress, ranged from `0.0` to `1.0`.
     *
     * There is no guarantee on the frequency of updates, but it should normally be updated at once per second.
     */
    public val playbackProgress: Flow<Float>

    /**
     * Additional features that are supported by the underlying player implementation.
     */
    public val features: PlayerFeatures

    /**
     * Sets the media data to play, updating [mediaData], and calling the underlying player implementation to start playing.
     *
     * This method is thread-safe and can be called from any thread, including the UI thread.
     *
     * Setting the same [MediaData] will be ignored.
     *
     * If the player is already playing a video, it will be stopped before playing the new video.
     *
     * @see stopPlayback
     */
    public suspend fun setMediaData(data: MediaData)

    /**
     * Gets the current playback state without suspension.
     *
     * To subscribe for updates, use [playbackState].
     *
     * @see playbackState
     */
    public fun getCurrentPlaybackState(): PlaybackState

    /**
     * Obtains the exact current playback position of the video in milliseconds, without suspension.
     *
     * If no video is being played, this function will return `0`.
     *
     * To subscribe for updates, use [currentPositionMillis].
     */
    public fun getCurrentPositionMillis(): Long

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
     * Stops playback, releasing all resources and setting [mediaData] to `null`.
     * Subsequent calls to [resume] will do nothing.
     *
     * [currentPositionMillis] will be reset to `0`.
     *
     * To play again, call [setMediaData].
     */
    public fun stopPlayback()

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
        seekTo(getCurrentPositionMillis() + deltaMillis)
    }

    /**
     * Closes the player, releasing all resources held by the player.
     *
     * This operation is permanent.
     * After [close], calling any method from the player will either result in an exception or have no effect.
     * Flows will emit no value.
     *
     * This function must be called on the UI thread as some backends may require it.
     */
    public override fun close()
}

@Suppress("DeprecatedCallableAddReplaceWith", "UnusedReceiverParameter")
@Deprecated(
    message = "'stop' is ambiguous. " +
            "To stop current playback, use `MediampPlayer.stopPlayback()`. " +
            "To close the player and release any background resources, use `MediampPlayer.close()`.",
    level = DeprecationLevel.ERROR,
)
public fun MediampPlayer.stop(): Nothing = throw NotImplementedError("stop")

/**
 * Plays the video at the specified [uri], e.g. a local file or a remote URL.
 */
public suspend fun MediampPlayer.playUri(uri: String): Unit =
    setMediaData(UriMediaData(uri, emptyMap(), MediaExtraFiles()))

/**
 * Toggles between [MediampPlayer.pause] and [MediampPlayer.resume] based on the current playback state.
 */
public fun MediampPlayer.togglePause() {
    if (getCurrentPlaybackState().isPlaying) {
        pause()
    } else {
        resume()
    }
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
@OptIn(InternalForInheritanceMediampApi::class)
public class DummyMediampPlayer(
    // TODO: 2024/12/22 move to preview package
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
) : AbstractMediampPlayer<AbstractMediampPlayer.Data>(parentCoroutineContext) {
    override val impl: Any get() = this
    override fun getCurrentPlaybackState(): PlaybackState {
        return playbackState.value
    }

    override val playbackState: MutableStateFlow<PlaybackState> = MutableStateFlow(PlaybackState.PLAYING)
    override fun stopPlaybackImpl() {
        currentPositionMillis.value = 0
        mediaProperties.value = null
        playbackState.value = PlaybackState.READY
        // TODO: 2025/1/5 We should encapsulate the mutable states to ensure consistency in flow emissions
    }

    override suspend fun setDataImpl(data: MediaData): Data {
        return Data(
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
        playbackState.value = PlaybackState.READY
        // no-op
    }

    override val mediaProperties: MutableStateFlow<MediaProperties?> = MutableStateFlow(
        MediaProperties(
            title = "Test Video",
            durationMillis = 100_000,
        ),
    )

    override fun getCurrentMediaProperties(): MediaProperties? = mediaProperties.value

    override val currentPositionMillis: MutableStateFlow<Long> = MutableStateFlow(10_000L)
    override fun getCurrentPositionMillis(): Long {
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

    override val features: PlayerFeatures = buildPlayerFeatures {
        add(
            PlaybackSpeed,
            object : PlaybackSpeed {
                override val valueFlow: MutableStateFlow<Float> = MutableStateFlow(1f)
                override val value: Float get() = valueFlow.value
                override fun set(speed: Float) {
                    valueFlow.value = speed
                }
            },
        )
        add(
            MediaMetadata,
            object : MediaMetadata {
                override val audioTracks: TrackGroup<AudioTrack> = emptyTrackGroup()
                override val subtitleTracks: TrackGroup<SubtitleTrack> = emptyTrackGroup()
                override val chapters: Flow<List<Chapter>> = MutableStateFlow(
                    listOf(
                        Chapter("chapter1", durationMillis = 90_000L, 0L),
                        Chapter("chapter2", durationMillis = 5_000L, 90_000L),
                    ),
                )
            },
        )
    }

    public object Factory : MediampPlayerFactory<DummyMediampPlayer> {
        override val forClass: KClass<DummyMediampPlayer> = DummyMediampPlayer::class

        override fun create(context: Any, parentCoroutineContext: CoroutineContext): DummyMediampPlayer {
            return DummyMediampPlayer(parentCoroutineContext)
        }
    }
}
