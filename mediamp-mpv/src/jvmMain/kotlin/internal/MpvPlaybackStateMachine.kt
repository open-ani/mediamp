/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import org.openani.mediamp.PlaybackState

/**
 * Pure bookkeeping that maps mpv property events and player commands onto [PlaybackState].
 *
 * Extracted from the player so the transition matrix (pause / paused-for-cache / eof /
 * idle / end-file-error, in every session phase) is unit-testable without native mpv.
 * Event methods return the state to publish, or `null` to keep the current state.
 */
internal class MpvPlaybackStateMachine {
    /** mpv's "pause" property as last reported/requested. */
    var pauseRequestedByUser: Boolean = false
        private set

    /** mpv's "paused-for-cache" property. */
    var pausedForCache: Boolean = false
        private set

    /** True between a successful loadfile/pause/resume command and EOF/stop/error. */
    var playbackSessionActive: Boolean = false
        private set

    /** True from a seek command until mpv reports "seeking" = false (gates stale time-pos). */
    @Volatile
    var seekInProgress: Boolean = false
        private set

    // ---- command side (called from player methods after the mpv command succeeded) ----

    /** `loadfile` succeeded from READY. */
    fun onPlaybackStarted() {
        playbackSessionActive = true
        pauseRequestedByUser = false
        pausedForCache = false
    }

    /** `pause=false` succeeded from PAUSED / PAUSED_BUFFERING. */
    fun onResumed() {
        playbackSessionActive = true
        pauseRequestedByUser = false
    }

    /** `pause=true` succeeded. */
    fun onPauseRequested() {
        playbackSessionActive = true
        pauseRequestedByUser = true
    }

    fun onSeekStarted() {
        seekInProgress = true
    }

    /** The seek command was rejected by mpv; stop gating time-pos. */
    fun onSeekRejected() {
        seekInProgress = false
    }

    fun reset() {
        pauseRequestedByUser = false
        pausedForCache = false
        playbackSessionActive = false
        seekInProgress = false
    }

    // ---- event side ----

    fun onPauseProperty(value: Boolean, current: PlaybackState): PlaybackState? {
        pauseRequestedByUser = value
        return sync(current)
    }

    fun onPausedForCacheProperty(value: Boolean, current: PlaybackState): PlaybackState? {
        pausedForCache = value
        return sync(current)
    }

    fun onSeekingProperty(value: Boolean) {
        if (!value) seekInProgress = false
    }

    /** True while a seek is settling: time-pos updates must not overwrite the optimistic position. */
    fun shouldIgnoreTimePos(): Boolean = seekInProgress

    fun onEofReachedProperty(value: Boolean, current: PlaybackState): PlaybackState? {
        if (value && playbackSessionActive && current >= PlaybackState.READY) {
            reset()
            return PlaybackState.FINISHED
        }
        return null
    }

    /**
     * With keep-open=always this only fires when loading fails or the file is unloaded
     * externally.
     * TODO: distinguish load errors (-> ERROR) from normal unloads.
     */
    fun onIdleActiveProperty(value: Boolean, current: PlaybackState): PlaybackState? {
        if (value && playbackSessionActive && current >= PlaybackState.READY) {
            reset()
            return PlaybackState.FINISHED
        }
        return null
    }

    /**
     * Maps load/demux failures ([MPV_END_FILE_REASON_ERROR]) to [PlaybackState.ERROR] so
     * downstream error handlers (e.g. automatic source switching) can react; EOF is handled
     * via "eof-reached", STOP/QUIT via stop()/close().
     */
    fun onEndFile(reason: Int, current: PlaybackState): PlaybackState? {
        if (reason == MPV_END_FILE_REASON_ERROR && playbackSessionActive && current >= PlaybackState.READY) {
            reset()
            return PlaybackState.ERROR
        }
        return null
    }

    private fun sync(current: PlaybackState): PlaybackState? {
        if (!playbackSessionActive || current < PlaybackState.READY) return null
        if (current == PlaybackState.FINISHED) {
            // keep-open pauses at EOF; don't resurrect PAUSED after we reported FINISHED.
            return null
        }
        return when {
            pauseRequestedByUser -> PlaybackState.PAUSED
            pausedForCache -> PlaybackState.PAUSED_BUFFERING
            else -> PlaybackState.PLAYING
        }
    }

    private companion object {
        private const val MPV_END_FILE_REASON_ERROR = 4
    }
}
