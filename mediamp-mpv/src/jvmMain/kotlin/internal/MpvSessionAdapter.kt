/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import kotlinx.coroutines.CompletableDeferred
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.PlaybackSessionHandle

/**
 * Per-media-session bookkeeping of the mpv backend, bridging the persistent mpv event
 * listener to the current [PlaybackSessionHandle].
 *
 * This is deliberately thin: all state transitions are owned by the shared machine
 * (`AbstractMediampPlayer`, spec `docs/playback-state-v2.md`). The adapter only keeps the
 * few native-side latches the machine cannot know:
 *
 * - [pendingOpen]: the Ready point of an open is `MPV_EVENT_FILE_LOADED`; an `END_FILE`
 *   arriving before it fails the open.
 * - [seekPending]: mpv fires `MPV_EVENT_PLAYBACK_RESTART` both after seeks and at initial
 *   file load; only a machine-issued seek may be reported as a seek completion.
 * - [eofReached]: mpv's keep-open auto-pause at EOF must not be reported as a transport
 *   change — it is part of the Ended fact (spec §5).
 *
 * Fields are `@Volatile`: they are written from the machine's main thread (command methods)
 * and from the mpv event thread; each individual latch has single-writer-per-edge semantics.
 */
@OptIn(InternalMediampApi::class)
internal class MpvSessionAdapter(
    val session: PlaybackSessionHandle,
) {
    /**
     * Completed normally at `MPV_EVENT_FILE_LOADED` (the Ready point, spec §3), or
     * exceptionally by an `END_FILE` that arrives before it. Remains set (completed) for the
     * session lifetime so a late `END_FILE(error)` can be told apart from an open failure via
     * [CompletableDeferred.completeExceptionally]'s return value.
     */
    @Volatile
    var pendingOpen: CompletableDeferred<Unit>? = null

    /**
     * True from a machine-issued `seekImpl` until its completion is taken by
     * [takeSeekCompletion]. The `playback-restart` fired at initial file load finds this
     * false and is not reported.
     */
    @Volatile
    var seekPending: Boolean = false

    /** Last observed `eof-reached` level; [onEofReachedChanged] detects the rising edge. */
    @Volatile
    var eofReached: Boolean = false

    /** Last observed `media-title`, merged with [lastDurationMillis] into MediaProperties. */
    @Volatile
    var lastTitle: String? = null

    /** Last observed duration in milliseconds; `null` = unknown (never a negative sentinel). */
    @Volatile
    var lastDurationMillis: Long? = null

    /**
     * Records the new `eof-reached` level and returns `true` on the rising edge — the one
     * observation that constitutes the Ended fact.
     */
    fun onEofReachedChanged(value: Boolean): Boolean {
        val was = eofReached
        eofReached = value
        return value && !was
    }

    /**
     * Consumes the pending-seek latch. Returns `true` when this `playback-restart` completes
     * a machine-issued seek and must be reported. mpv coalesces rapid seeks into a single
     * restart; latest-generation attribution (reading `currentSeekGeneration` at processing
     * time) closes every superseded generation at once (spec §5).
     */
    fun takeSeekCompletion(): Boolean {
        val pending = seekPending
        seekPending = false
        return pending
    }
}

// mpv_end_file_reason (mpv/client.h)
internal const val MPV_END_FILE_REASON_EOF: Int = 0
internal const val MPV_END_FILE_REASON_ERROR: Int = 4

// mpv_error (mpv/client.h)
private const val MPV_ERROR_LOADING_FAILED: Int = -13
private const val MPV_ERROR_NOTHING_TO_PLAY: Int = -16
private const val MPV_ERROR_UNKNOWN_FORMAT: Int = -17
private const val MPV_ERROR_UNSUPPORTED: Int = -18

/**
 * Maps an `mpv_error` code (delivered with `END_FILE(reason=error)`) to a [PlaybackException].
 */
internal fun mpvErrorToPlaybackException(mpvError: Int): PlaybackException {
    val code = when (mpvError) {
        MPV_ERROR_LOADING_FAILED -> PlaybackErrorCode.IO
        MPV_ERROR_NOTHING_TO_PLAY,
        MPV_ERROR_UNKNOWN_FORMAT,
        MPV_ERROR_UNSUPPORTED,
        -> PlaybackErrorCode.UNSUPPORTED_FORMAT

        else -> PlaybackErrorCode.INTERNAL
    }
    return PlaybackException(code, "mpv playback failed (mpv_error=$mpvError)")
}
