/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import org.openani.mediamp.PlaybackState
import org.openani.mediamp.mpv.internal.MpvPlaybackStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvPlaybackStateMachineTest {

    private val machine = MpvPlaybackStateMachine()

    private fun startedPlaying(): MpvPlaybackStateMachine = machine.also { it.onPlaybackStarted() }

    // ---- pause / resume ----

    @Test
    fun `pause property before any session is ignored`() {
        assertNull(machine.onPauseProperty(true, PlaybackState.READY))
        assertNull(machine.onPauseProperty(false, PlaybackState.READY))
    }

    @Test
    fun `pause property toggles PAUSED and PLAYING during a session`() {
        val m = startedPlaying()
        assertEquals(PlaybackState.PAUSED, m.onPauseProperty(true, PlaybackState.PLAYING))
        assertEquals(PlaybackState.PLAYING, m.onPauseProperty(false, PlaybackState.PAUSED))
    }

    @Test
    fun `pause events in CREATED state are ignored even with active session`() {
        val m = startedPlaying()
        assertNull(m.onPauseProperty(true, PlaybackState.CREATED))
    }

    // ---- buffering ----

    @Test
    fun `cache stall maps to PAUSED_BUFFERING and recovers to PLAYING`() {
        val m = startedPlaying()
        assertEquals(PlaybackState.PAUSED_BUFFERING, m.onPausedForCacheProperty(true, PlaybackState.PLAYING))
        assertEquals(PlaybackState.PLAYING, m.onPausedForCacheProperty(false, PlaybackState.PAUSED_BUFFERING))
    }

    @Test
    fun `user pause wins over cache stall`() {
        val m = startedPlaying()
        m.onPausedForCacheProperty(true, PlaybackState.PLAYING)
        assertEquals(PlaybackState.PAUSED, m.onPauseProperty(true, PlaybackState.PAUSED_BUFFERING))
        // cache recovers while user pause holds -> stay PAUSED
        assertEquals(PlaybackState.PAUSED, m.onPausedForCacheProperty(false, PlaybackState.PAUSED))
    }

    @Test
    fun `starting playback clears stale pause and cache flags`() {
        machine.onPauseRequested()
        machine.onPausedForCacheProperty(true, PlaybackState.PAUSED)
        machine.onPlaybackStarted()
        // Neither stale flag may demote the fresh session.
        assertEquals(PlaybackState.PLAYING, machine.onPausedForCacheProperty(false, PlaybackState.PLAYING))
        assertFalse(machine.pauseRequestedByUser)
    }

    // ---- FINISHED latching ----

    @Test
    fun `eof maps to FINISHED and resets the session`() {
        val m = startedPlaying()
        assertEquals(PlaybackState.FINISHED, m.onEofReachedProperty(true, PlaybackState.PLAYING))
        assertFalse(m.playbackSessionActive)
        // keep-open reports pause=true after EOF; it must not resurrect PAUSED.
        assertNull(m.onPauseProperty(true, PlaybackState.FINISHED))
    }

    @Test
    fun `eof=false is a no-op`() {
        assertNull(startedPlaying().onEofReachedProperty(false, PlaybackState.PLAYING))
    }

    @Test
    fun `eof without active session is ignored`() {
        assertNull(machine.onEofReachedProperty(true, PlaybackState.PLAYING))
    }

    @Test
    fun `pause change while FINISHED keeps FINISHED even with active session`() {
        val m = startedPlaying()
        // Session still active (e.g. keep-open), state already FINISHED via stop().
        assertNull(m.onPauseProperty(false, PlaybackState.FINISHED))
    }

    @Test
    fun `idle-active behaves like eof`() {
        val m = startedPlaying()
        assertEquals(PlaybackState.FINISHED, m.onIdleActiveProperty(true, PlaybackState.PLAYING))
        assertNull(machine.onIdleActiveProperty(true, PlaybackState.PLAYING)) // session gone
    }

    // ---- end-file error mapping ----

    @Test
    fun `end-file with error reason maps to ERROR and resets`() {
        val m = startedPlaying()
        assertEquals(PlaybackState.ERROR, m.onEndFile(reason = 4, current = PlaybackState.PLAYING))
        assertFalse(m.playbackSessionActive)
    }

    @Test
    fun `end-file with non-error reasons is ignored`() {
        val m = startedPlaying()
        for (reason in intArrayOf(0, 1, 2, 3, 5)) {
            assertNull(m.onEndFile(reason, PlaybackState.PLAYING), "reason=$reason")
        }
        assertTrue(m.playbackSessionActive)
    }

    @Test
    fun `end-file error before session start is ignored`() {
        assertNull(machine.onEndFile(4, PlaybackState.READY))
    }

    @Test
    fun `end-file error after FINISHED is ignored`() {
        val m = startedPlaying()
        m.onEofReachedProperty(true, PlaybackState.PLAYING)
        assertNull(m.onEndFile(4, PlaybackState.FINISHED))
    }

    // ---- seek gating ----

    @Test
    fun `time-pos is gated while a seek settles`() {
        machine.onSeekStarted()
        assertTrue(machine.shouldIgnoreTimePos())
        machine.onSeekingProperty(false)
        assertFalse(machine.shouldIgnoreTimePos())
    }

    @Test
    fun `seeking=true does not clear the gate`() {
        machine.onSeekStarted()
        machine.onSeekingProperty(true)
        assertTrue(machine.shouldIgnoreTimePos())
    }

    @Test
    fun `rejected seek clears the gate`() {
        machine.onSeekStarted()
        machine.onSeekRejected()
        assertFalse(machine.shouldIgnoreTimePos())
    }

    @Test
    fun `session end clears the seek gate`() {
        val m = startedPlaying()
        m.onSeekStarted()
        m.onEofReachedProperty(true, PlaybackState.PLAYING)
        assertFalse(m.shouldIgnoreTimePos())
    }

    // ---- resume command semantics ----

    @Test
    fun `resume after pause keeps cache flag intact`() {
        val m = startedPlaying()
        m.onPausedForCacheProperty(true, PlaybackState.PLAYING)
        m.onPauseProperty(true, PlaybackState.PAUSED_BUFFERING)
        m.onResumed()
        // user pause cleared, but the cache stall is still in effect
        assertEquals(PlaybackState.PAUSED_BUFFERING, m.onPausedForCacheProperty(true, PlaybackState.PLAYING))
    }
}
