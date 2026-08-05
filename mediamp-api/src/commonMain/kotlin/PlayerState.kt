/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

/**
 * An atomic snapshot of the player state, combining three orthogonal axes.
 *
 * This is the canonical state model of [MediampPlayer] (spec: `docs/playback-state-v2.md`).
 * Collect [MediampPlayer.state] to observe it. Emissions are serialized, in order, and never
 * torn across axes — each snapshot is internally consistent.
 *
 * The three axes:
 * - [mediaStatus]: where the media session is in its lifecycle.
 * - [playWhenReady]: whether the user/app wants playback running. Flipped synchronously by
 *   [MediampPlayer.play]/[MediampPlayer.pause]; never changed by buffering.
 * - [isBuffering]: whether playback cannot advance at the current position because data is
 *   not ready (initial prefetch, mid-play stall, post-seek stall).
 *
 * Invariants:
 * - `isBuffering` implies `mediaStatus == MediaStatus.Ready`.
 * - `playWhenReady` implies `mediaStatus` is [MediaStatus.Opening] or [MediaStatus.Ready];
 *   entering [MediaStatus.Ended], [MediaStatus.Error] or [MediaStatus.Released] resets
 *   `playWhenReady` to `false` in the same atomic emission.
 */
public data class PlayerState(
    val mediaStatus: MediaStatus,
    val playWhenReady: Boolean,
    val isBuffering: Boolean,
) {
    /**
     * Whether the playback clock is actually advancing (frames are being rendered).
     *
     * Note this is strict: it is `false` while buffering even if the user pressed play.
     * To drive a play/pause button icon, use [playWhenReady] instead.
     */
    val isPlaying: Boolean get() = mediaStatus == MediaStatus.Ready && playWhenReady && !isBuffering

    public companion object {
        /**
         * The initial state of a newly created player: no media, paused, not buffering.
         */
        public val Initial: PlayerState = PlayerState(MediaStatus.Idle, playWhenReady = false, isBuffering = false)
    }
}

/**
 * The lifecycle axis of [PlayerState]: where the media session is.
 *
 * Exhaustive `when` over this type has 6 cases. Ordinal comparison (the v1
 * [PlaybackState] contract) is intentionally not supported; use the predicates in
 * [PlayerState] and the `is`/`==` operators.
 */
public sealed class MediaStatus {
    /**
     * No media loaded. This is the initial status, and also the status after
     * [MediampPlayer.stopPlayback].
     */
    public data object Idle : MediaStatus()

    /**
     * A [MediampPlayer.setMediaData] call is in flight: the media is being opened and
     * prepared by the backend. Ends in [Ready], [Error], or back to [Idle] (stopped /
     * cancelled).
     */
    public data object Opening : MediaStatus()

    /**
     * Media is opened: the source was accepted and metadata is available.
     *
     * This is NOT a first-frame or buffer-level guarantee — [PlayerState.isBuffering]
     * reports data availability.
     */
    public data object Ready : MediaStatus()

    /**
     * The playhead reached the end of the media (played through, or sought to the end where
     * the backend can determine it). Never produced by [MediampPlayer.stopPlayback].
     *
     * The media stays loaded: [MediampPlayer.mediaData] is retained and replay is cheap —
     * call [MediampPlayer.play] to restart from the beginning, or [MediampPlayer.seekTo] to
     * re-enter [Ready] paused at a position.
     *
     * Prefer reacting to the `MediaEnded` event on [MediampPlayer.events] for actions that
     * advance the session (e.g. auto-play-next); use this status for passive UI.
     */
    public data object Ended : MediaStatus()

    /**
     * A fatal error occurred. [error] carries the cause. Resources have already been
     * released ([MediampPlayer.mediaData] is `null`).
     *
     * Recovery: [MediampPlayer.setMediaData] with a fresh [org.openani.mediamp.source.MediaData],
     * [MediampPlayer.stopPlayback] to dismiss to [Idle], or [MediampPlayer.close].
     */
    public data class Error(public val error: PlaybackException) : MediaStatus()

    /**
     * [MediampPlayer.close] was called. Terminal: no commands act and no flows emit, ever.
     */
    public data object Released : MediaStatus()
}

/**
 * Whether a loading indicator should be shown: the media is being opened, or playback is
 * stalled for data.
 */
public val PlayerState.isLoadingOrBuffering: Boolean
    get() = mediaStatus == MediaStatus.Opening || isBuffering

/**
 * Whether a media is currently loaded (opened or ended, resources held).
 */
public val PlayerState.isMediaLoaded: Boolean
    get() = mediaStatus == MediaStatus.Ready || mediaStatus == MediaStatus.Ended

/**
 * The error of the current [MediaStatus.Error] status, or `null` if not in error.
 */
public val PlayerState.errorOrNull: PlaybackException?
    get() = (mediaStatus as? MediaStatus.Error)?.error
