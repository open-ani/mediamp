/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData

/**
 * An extensible media player that plays [MediaData]s. Instances can be obtained from a
 * [MediampPlayerFactory].
 *
 * ## State model
 *
 * The player state is the atomic snapshot [PlayerState], observed via [state]:
 * three orthogonal axes — [PlayerState.mediaStatus] (lifecycle), [PlayerState.playWhenReady]
 * (intent), [PlayerState.isBuffering] (data availability). The normative specification lives
 * in `docs/playback-state-v2.md`; the KDoc here is a summary.
 *
 * Common consumer patterns:
 * ```kotlin
 * // Play/pause button:
 * val icon = if (state.playWhenReady) PauseIcon else PlayIcon      // icon
 * onClick = { player.togglePlayWhenReady() }                       // never dead
 * // Loading spinner:
 * val showSpinner = state.isLoadingOrBuffering
 * // Ended screen (passive UI):
 * val showEnded = state.mediaStatus == MediaStatus.Ended
 * // Auto-play-next (advances the session — use events, not state):
 * player.events.filterIsInstance<PlaybackEvent.MediaEnded>().collect { ... }
 * // Error UI:
 * val error = state.errorOrNull
 * ```
 *
 * ## Threading
 *
 * [play], [pause], [stopPlayback], [seekTo] and [skip] must be called on the thread of
 * [mainDispatcher] (the platform UI thread by default); violations throw
 * [IllegalStateException]. [setMediaData] and [close] may be called from any thread.
 *
 * ## Not safe for inheritance
 *
 * [MediampPlayer] is not safe for third-party inheritance: new members may be added.
 * Extend [AbstractMediampPlayer] to implement a backend.
 */
@SubclassOptInRequired(InternalForInheritanceMediampApi::class)
public interface MediampPlayer : AutoCloseable {
    /**
     * The underlying player implementation. Cast to the backend type for features not yet
     * ported by Mediamp. Do NOT call methods that change transport or lifecycle state —
     * doing so desynchronizes the state machine.
     */
    public val impl: Any

    /**
     * The canonical player state: serialized, in-order, atomic snapshots.
     * @see PlayerState
     */
    public val state: StateFlow<PlayerState>

    /**
     * Edge-type playback facts, delivered losslessly to subscribed, keeping-up collectors.
     * Use for reactions that advance the session (auto-play-next); use [state] for UI.
     * Subscribe before issuing commands; there is no replay.
     * @see PlaybackEvent
     */
    public val events: SharedFlow<PlaybackEvent>

    /**
     * The currently loaded media, or `null` when [PlayerState.mediaStatus] is
     * [MediaStatus.Idle], [MediaStatus.Opening], [MediaStatus.Error] or [MediaStatus.Released].
     * Retained at [MediaStatus.Ended].
     */
    public val mediaData: StateFlow<MediaData?>

    /**
     * Properties (title, duration) of the loaded media; `null` while unknown.
     * [MediaProperties.durationMillis] is `null` for live/unknown-duration media.
     */
    public val mediaProperties: StateFlow<MediaProperties?>

    /**
     * Current playback position in milliseconds. `0` when no media is loaded.
     * Updated optimistically on [seekTo].
     */
    public val currentPositionMillis: StateFlow<Long>

    /**
     * Playback progress in `0f..1f`; `0f` when duration is unknown.
     */
    public val playbackProgress: Flow<Float>

    /**
     * Additional features supported by the backend.
     */
    public val features: PlayerFeatures

    /**
     * The dispatcher the player's state machine is confined to. Playback commands must be
     * called on its thread.
     */
    public val mainDispatcher: CoroutineDispatcher

    /**
     * Loads [data], replacing any current media.
     *
     * Emits [MediaStatus.Opening], then suspends until the media is truly opened (metadata
     * available) — a nonexistent/unreachable/unsupported source fails inside this call on
     * every backend.
     *
     * On normal return, [MediaStatus.Ready] has been committed with the requested
     * [playWhenReady] intent and [startPositionMillis] applied (a start position at/beyond
     * the media end advances to [MediaStatus.Ended] in the same turn).
     *
     * Autoplay is explicit: the default `playWhenReady = false` loads paused; pass `true` or
     * call [play] after.
     *
     * @throws PlaybackException if opening fails (also emitted as [MediaStatus.Error]).
     * @throws MediaLoadCancellationException if superseded by a newer call, or aborted by
     *   [stopPlayback]/[close]. Callers catching it must `ensureActive()` before recovery.
     */
    public suspend fun setMediaData(
        data: MediaData,
        playWhenReady: Boolean = false,
        startPositionMillis: Long = 0L,
    )

    /**
     * Sets the play intent to `true`, synchronously ([state]`.value.playWhenReady` is `true`
     * on return when media is loaded or opening). At [MediaStatus.Ended] this replays from
     * the beginning. No-op at [MediaStatus.Idle], [MediaStatus.Error], [MediaStatus.Released].
     */
    public fun play()

    /**
     * Sets the play intent to `false`, synchronously. Never dropped — including during
     * buffering and opening.
     */
    public fun pause()

    /**
     * Unloads the current media (or aborts an in-flight [setMediaData]) and returns to
     * [MediaStatus.Idle], releasing the [MediaData]. Never produces [MediaStatus.Ended].
     */
    public fun stopPlayback()

    /**
     * Seeks to [positionMillis], clamped to the media duration when known.
     *
     * While [MediaStatus.Ready]: [currentPositionMillis] updates optimistically; a paused
     * seek stays paused and the new frame is displayed. At [MediaStatus.Ended]: returns to
     * [MediaStatus.Ready] paused at the position. During [MediaStatus.Opening]: adjusts the
     * start position. Otherwise no-op.
     */
    public fun seekTo(positionMillis: Long)

    /**
     * Skips by [deltaMillis] (negative = backward). See [seekTo].
     */
    public fun skip(deltaMillis: Long) {
        seekTo(currentPositionMillis.value + deltaMillis)
    }

    /**
     * Closes the player permanently: emits [MediaStatus.Released], after which no command
     * acts and no flow emits. Callable from any thread; idempotent.
     */
    public override fun close()

    // region deprecated v1 API

    /**
     * The v1 enum state, derived from [state].
     *
     * Mapping notes (see `docs/playback-state-v2.md` §2): [stopPlayback] now yields
     * [PlaybackState.CREATED] (was FINISHED); [PlaybackState.READY] is derived while the
     * media has never played since open; [PlaybackState.FINISHED] means natural end only.
     */
    @Deprecated("Use state (PlayerState) instead. See docs/playback-state-v2.md.")
    @Suppress("DEPRECATION")
    public val playbackState: StateFlow<PlaybackState>

    @Deprecated("Renamed to play().", ReplaceWith("play()"))
    public fun resume() {
        play()
    }

    @Deprecated("Use state.value instead.", ReplaceWith("state.value"))
    @Suppress("DEPRECATION")
    public fun getCurrentPlaybackState(): PlaybackState = playbackState.value

    @Deprecated("Use currentPositionMillis.value instead.", ReplaceWith("currentPositionMillis.value"))
    public fun getCurrentPositionMillis(): Long = currentPositionMillis.value

    @Deprecated("Use mediaProperties.value instead.", ReplaceWith("mediaProperties.value"))
    public fun getCurrentMediaProperties(): MediaProperties? = mediaProperties.value
    // endregion
}

/**
 * Toggles the play/pause intent. Never dead in any loaded state: flips intent at
 * [MediaStatus.Ready]/[MediaStatus.Opening], replays at [MediaStatus.Ended].
 */
public fun MediampPlayer.togglePlayWhenReady() {
    val s = state.value
    when (s.mediaStatus) {
        MediaStatus.Ended -> play()
        MediaStatus.Ready, MediaStatus.Opening -> if (s.playWhenReady) pause() else play()
        else -> {}
    }
}

/**
 * v1-compatible toggle: acts only when the derived legacy state is PLAYING or PAUSED, so UIs
 * that derive their icon from `isPlaying` keep v1 behavior during migration.
 */
@Suppress("DEPRECATION")
@Deprecated(
    "Use togglePlayWhenReady(), which is never dead during buffering, and drive the button " +
            "icon from state.value.playWhenReady.",
    ReplaceWith("togglePlayWhenReady()"),
)
public fun MediampPlayer.togglePause() {
    when (playbackState.value) {
        PlaybackState.PLAYING -> pause()
        PlaybackState.PAUSED -> play()
        else -> {}
    }
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
 * Plays the media at [uri] (local file or remote URL).
 */
public suspend fun MediampPlayer.playUri(
    uri: String,
    playWhenReady: Boolean = true,
    startPositionMillis: Long = 0L,
): Unit = setMediaData(UriMediaData(uri, emptyMap(), MediaExtraFiles()), playWhenReady, startPositionMillis)

/**
 * A distinct flow of [PlayerState.isPlaying] (the clock actually advancing).
 */
public fun MediampPlayer.isPlayingFlow(): Flow<Boolean> =
    state.map { it.isPlaying }.distinctUntilChanged()

/**
 * A distinct flow of [PlayerState.isLoadingOrBuffering] — drive a loading spinner with this.
 */
public fun MediampPlayer.isLoadingOrBufferingFlow(): Flow<Boolean> =
    state.map { it.isLoadingOrBuffering }.distinctUntilChanged()
