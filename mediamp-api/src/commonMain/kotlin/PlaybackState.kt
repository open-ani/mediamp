/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp


/**
 * The v1 playback state enum, retained for migration.
 *
 * Superseded by [PlayerState] (three orthogonal axes; see `docs/playback-state-v2.md`).
 * The deprecated [MediampPlayer.playbackState] flow derives these values from [PlayerState]:
 *
 * - [CREATED]: [MediaStatus.Idle] or [MediaStatus.Opening]. Note: [MediampPlayer.stopPlayback]
 *   now yields CREATED (v1 yielded FINISHED).
 * - [READY]: [MediaStatus.Ready] while the media has never played since open.
 * - [PLAYING] / [PAUSED] / [PAUSED_BUFFERING]: [MediaStatus.Ready] after first playback,
 *   split by intent x buffering.
 * - [FINISHED]: [MediaStatus.Ended] — natural end of media only.
 * - [ERROR]: [MediaStatus.Error]; the cause is available via [PlayerState.errorOrNull].
 * - [DESTROYED]: [MediaStatus.Released].
 *
 * The v1 ordinal-comparison contract (`state >= PAUSED` etc.) is withdrawn: the ordering was
 * semantically unsound (PAUSED_BUFFERING sorted above PLAYING) and effectively unused.
 * Compare with `==` or migrate to [PlayerState] predicates.
 */
@Deprecated("Use PlayerState/MediaStatus instead. See docs/playback-state-v2.md.")
public enum class PlaybackState {
    DESTROYED,
    ERROR,
    CREATED,
    FINISHED,
    READY,
    PAUSED,
    PLAYING,
    PAUSED_BUFFERING,
    ;
}

@Deprecated(
    "Use PlayerState.isPlaying via MediampPlayer.state instead.",
    ReplaceWith("this == PlaybackState.PLAYING"),
)
@Suppress("DEPRECATION")
public val PlaybackState.isPlaying: Boolean
    get() = this == PlaybackState.PLAYING
