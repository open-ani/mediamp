/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.CompletableDeferred
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.PlaybackSessionHandle
import org.openani.mediamp.TransportSnapshot
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.mpv.internal.MpvSessionAdapter
import org.openani.mediamp.mpv.internal.mpvErrorToPlaybackException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the native-side latches of [MpvSessionAdapter] (no native mpv needed).
 * The state transitions themselves belong to the shared machine and are covered by the
 * core/conformance suites; the gated integration tests cover the real event wiring.
 */
@OptIn(InternalMediampApi::class)
class MpvSessionAdapterTest {

    private class NoopSessionHandle : PlaybackSessionHandle {
        override val isValid: Boolean get() = true
        override val currentSeekGeneration: Int get() = 0
        override fun reportTransport(snapshot: TransportSnapshot) {}
        override fun notifyEnded() {}
        override fun notifyError(error: PlaybackException) {}
        override fun notifySeekCompleted(seekGeneration: Int, positionMillis: Long, snapshot: TransportSnapshot) {}
        override fun notifyProperties(properties: MediaProperties) {}
        override fun notifyPosition(positionMillis: Long) {}
    }

    private val adapter = MpvSessionAdapter(NoopSessionHandle())

    // ---- eof-reached edge detection (the Ended fact) ----

    @Test
    fun `eof-reached rising edge fires exactly once`() {
        assertTrue(adapter.onEofReachedChanged(true))
        // mpv may re-notify the same level; only the edge is the Ended fact.
        assertFalse(adapter.onEofReachedChanged(true))
    }

    @Test
    fun `eof-reached falling edge is not an Ended fact`() {
        adapter.onEofReachedChanged(true)
        assertFalse(adapter.onEofReachedChanged(false))
        // After a replay seek cleared it, a new natural EOF is a fresh edge.
        assertTrue(adapter.onEofReachedChanged(true))
    }

    @Test
    fun `pre-set eofReached suppresses the edge - at-end open handoff`() {
        // openImpl reports atEnd in the OpenResult and pre-sets the latch so the property
        // notification does not double-report Ended.
        adapter.eofReached = true
        assertFalse(adapter.onEofReachedChanged(true))
    }

    // ---- seek completion attribution (SEEK / PLAYBACK_RESTART pairing) ----

    @Test
    fun `initial playback-restart is not a seek completion`() {
        adapter.onFileLoaded()
        assertEquals(0, adapter.onPlaybackRestart())
    }

    @Test
    fun `SEEK and restart events before FILE_LOADED belong to a previous file`() {
        adapter.onSeekEvent() // queued from the previously unloaded file
        assertEquals(0, adapter.onPlaybackRestart())
        adapter.onFileLoaded()
        assertEquals(0, adapter.onPlaybackRestart())
    }

    @Test
    fun `machine seek completes exactly once via its SEEK then restart`() {
        adapter.onFileLoaded()
        adapter.onSeekIssued(1)
        adapter.onSeekEvent()
        assertEquals(1, adapter.onPlaybackRestart())
        // mpv may fire further restarts (track switch etc.); they complete nothing.
        assertEquals(0, adapter.onPlaybackRestart())
    }

    @Test
    fun `seek issued right after load is not completed by the initial restart`() {
        // regression: a seekTo() issued right after setMediaData() returns armed the old
        // boolean latch, and the initial-load restart consumed it — reporting a completion
        // at the pre-seek position and closing the gate early.
        adapter.onFileLoaded()
        adapter.onSeekIssued(1)
        assertEquals(0, adapter.onPlaybackRestart()) // initial-load restart: no SEEK stamp
        adapter.onSeekEvent() // the machine seek actually executes
        assertEquals(1, adapter.onPlaybackRestart()) // its own restart completes it
    }

    @Test
    fun `the open's start-position seek consumes the budget and completes nothing`() {
        val adapter = MpvSessionAdapter(NoopSessionHandle(), openStartSeekExpected = true)
        adapter.onFileLoaded()
        adapter.onSeekEvent() // loadfile ... start= positioning
        assertEquals(0, adapter.onPlaybackRestart())
        adapter.onSeekIssued(1) // a real machine seek afterwards
        adapter.onSeekEvent()
        assertEquals(1, adapter.onPlaybackRestart())
    }

    @Test
    fun `a restart stamped by an older generation does not close a newer seek`() {
        // regression: the old latch let seek G's restart be misattributed to G+1, closing
        // the newer seek's gate before it landed.
        adapter.onFileLoaded()
        adapter.onSeekIssued(1)
        adapter.onSeekEvent() // SEEK belonging to generation 1
        adapter.onSeekIssued(2) // newer seek issued before the restart drains
        assertEquals(0, adapter.onPlaybackRestart()) // must not close generation 2 early
        adapter.onSeekEvent() // generation 2 executes
        assertEquals(2, adapter.onPlaybackRestart()) // closes generations <= 2
    }

    @Test
    fun `synchronous seek rejection synthesizes exactly one completion`() {
        adapter.onFileLoaded()
        adapter.onSeekIssued(1)
        assertTrue(adapter.onSeekRejected(1)) // caller synthesizes the completion
        assertFalse(adapter.onSeekRejected(1)) // idempotent
        assertEquals(0, adapter.onPlaybackRestart()) // no stray completion afterwards
    }

    // ---- END_FILE attribution by playlist entry id ----

    @Test
    fun `end-file entry-id staleness follows ceiling then binding`() {
        val adapter = MpvSessionAdapter(NoopSessionHandle(), staleEntryIdCeiling = 5L)
        // regression: an episode switch's queued END_FILE(STOP) of the OLD file draining
        // after the new adapter installed spuriously failed the healthy new open.
        assertTrue(adapter.isStaleEndFile(3L)) // at/below the ceiling: stale before binding
        assertTrue(adapter.isStaleEndFile(5L))
        assertFalse(adapter.isStaleEndFile(6L)) // newer than the ceiling, unbound: ours
        adapter.bindEntryId(7L)
        assertTrue(adapter.isStaleEndFile(6L)) // bound: any other id is stale
        assertFalse(adapter.isStaleEndFile(7L))
        // Old natives without entry-id support never classify as stale.
        assertFalse(adapter.isStaleEndFile(0L))
        assertFalse(adapter.isStaleEndFile(-1L))
    }

    // ---- open failure routing ----

    @Test
    fun `end-file before FILE_LOADED fails the open, after it routes mid-session`() {
        val opened = CompletableDeferred<Unit>()
        adapter.pendingOpen = opened

        // Before the Ready point: completeExceptionally wins -> the open throws.
        assertTrue(opened.completeExceptionally(mpvErrorToPlaybackException(-13)))

        // After completion (Ready reached), the same call reports false -> the caller
        // must route the error as an asynchronous mid-session fact instead.
        val openedSecond = CompletableDeferred<Unit>()
        adapter.pendingOpen = openedSecond
        openedSecond.complete(Unit)
        assertFalse(openedSecond.completeExceptionally(mpvErrorToPlaybackException(-13)))
    }

    // ---- mpv_error mapping ----

    @Test
    fun `mpv error codes map to PlaybackErrorCodes`() {
        assertEquals(PlaybackErrorCode.IO, mpvErrorToPlaybackException(-13).code) // LOADING_FAILED
        assertEquals(PlaybackErrorCode.UNSUPPORTED_FORMAT, mpvErrorToPlaybackException(-16).code) // NOTHING_TO_PLAY
        assertEquals(PlaybackErrorCode.UNSUPPORTED_FORMAT, mpvErrorToPlaybackException(-17).code) // UNKNOWN_FORMAT
        assertEquals(PlaybackErrorCode.UNSUPPORTED_FORMAT, mpvErrorToPlaybackException(-18).code) // UNSUPPORTED
        assertEquals(PlaybackErrorCode.INTERNAL, mpvErrorToPlaybackException(-20).code) // GENERIC
        assertEquals(PlaybackErrorCode.INTERNAL, mpvErrorToPlaybackException(0).code)
    }
}
