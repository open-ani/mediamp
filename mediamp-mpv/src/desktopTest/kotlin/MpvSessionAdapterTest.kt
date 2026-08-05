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

    // ---- seek completion latch (playback-restart attribution) ----

    @Test
    fun `initial playback-restart is not a seek completion`() {
        assertFalse(adapter.takeSeekCompletion())
    }

    @Test
    fun `machine-issued seek is completed exactly once`() {
        adapter.seekPending = true
        assertTrue(adapter.takeSeekCompletion())
        // mpv coalesces N queued seeks into one restart; a second restart must not
        // fabricate another completion.
        assertFalse(adapter.takeSeekCompletion())
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
