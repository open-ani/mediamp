/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.vlc

import org.openani.mediamp.vlc.internal.VlcSeekCoordinator
import org.openani.mediamp.vlc.internal.VlcSeekCoordinator.Companion.NONE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VlcSeekCoordinatorTest {
    private var now = 0L
    private val coordinator = VlcSeekCoordinator(
        minSubmitIntervalMillis = 250,
        settleWindowMillis = 1_000,
        acceptToleranceMillis = 2_000,
        clock = { now },
    )

    @Test
    fun `no seek in flight - reports pass through`() {
        assertTrue(coordinator.onPositionReported(1234).acceptPosition)
    }

    @Test
    fun `first seek submits immediately`() {
        assertEquals(8000, coordinator.requestSeek(8000))
    }

    @Test
    fun `requests inside the throttle interval are queued, latest wins`() {
        assertEquals(8000, coordinator.requestSeek(8000))
        now += 100
        assertEquals(NONE, coordinator.requestSeek(13000))
        now += 100
        assertEquals(NONE, coordinator.requestSeek(18000))
        now += 60 // 260 ms since submit
        assertEquals(18000, coordinator.flushQueued())
        assertEquals(NONE, coordinator.flushQueued()) // idempotent
    }

    @Test
    fun `request after the interval submits directly`() {
        coordinator.requestSeek(8000)
        now += 300
        assertEquals(13000, coordinator.requestSeek(13000))
    }

    @Test
    fun `stale and pulled-back reports are dropped within the settle window`() {
        coordinator.requestSeek(8000)
        now += 50
        assertFalse(coordinator.onPositionReported(3000).acceptPosition) // pre-seek position
        now += 500
        assertFalse(coordinator.onPositionReported(3200).acceptPosition) // demuxer pull-back
    }

    @Test
    fun `clock echo of the current target is accepted`() {
        coordinator.requestSeek(8000)
        now += 10
        assertTrue(coordinator.onPositionReported(8000).acceptPosition)
        now += 200
        assertTrue(coordinator.onPositionReported(8190).acceptPosition)
    }

    @Test
    fun `echo of a superseded target is dropped while a newer target is queued`() {
        coordinator.requestSeek(8000)
        now += 100
        coordinator.requestSeek(13000) // queued; latest intent is now 13000
        now += 10
        // clock echo of the already-submitted 8000: 5000 away from intent -> drop
        assertFalse(coordinator.onPositionReported(8000).acceptPosition)
    }

    @Test
    fun `onPositionReported opportunistically flushes an overdue queued target`() {
        coordinator.requestSeek(8000)
        now += 100
        assertEquals(NONE, coordinator.requestSeek(13000))
        now += 200 // 300 ms since submit: overdue
        val report = coordinator.onPositionReported(8010)
        assertEquals(13000, report.submitTarget)
        // 8010 is 4990 away from the just-submitted intent 13000 -> dropped
        assertFalse(report.acceptPosition)
    }

    @Test
    fun `expired window with far report retries the intent instead of surrendering`() {
        coordinator.requestSeek(60000)
        now += 1_100
        // VLC is still at an old position: resubmit the intent, keep holding
        val r1 = coordinator.onPositionReported(3000)
        assertFalse(r1.acceptPosition)
        assertEquals(60000, r1.submitTarget)
        // within the renewed window, stale reports stay dropped
        now += 500
        assertFalse(coordinator.onPositionReported(3200).acceptPosition)
        // VLC finally lands near the intent -> accepted
        now += 300
        assertTrue(coordinator.onPositionReported(59900).acceptPosition)
    }

    @Test
    fun `gate opens fully after retries are exhausted`() {
        coordinator.requestSeek(60000)
        repeat(2) { // maxRetries
            now += 1_100
            val r = coordinator.onPositionReported(3000)
            assertFalse(r.acceptPosition)
            assertEquals(60000, r.submitTarget)
        }
        now += 1_100
        assertTrue(coordinator.onPositionReported(3000).acceptPosition) // gave up; follow reality
        assertTrue(coordinator.onPositionReported(3100).acceptPosition)
    }

    @Test
    fun `a new user seek restores the retry budget`() {
        coordinator.requestSeek(60000)
        repeat(2) {
            now += 1_100
            coordinator.onPositionReported(3000)
        }
        now += 300
        coordinator.requestSeek(90000) // fresh intent
        now += 1_100
        val r = coordinator.onPositionReported(3000)
        assertFalse(r.acceptPosition) // retry budget is available again
        assertEquals(90000, r.submitTarget)
    }

    @Test
    fun `each submission restarts the settle window`() {
        coordinator.requestSeek(8000)
        now += 300
        coordinator.requestSeek(60000) // submits (interval elapsed), window restarts
        now += 900 // 1200 ms after first submit, 900 ms after second
        assertFalse(coordinator.onPositionReported(8000).acceptPosition) // still gated vs 60000
        assertTrue(coordinator.onPositionReported(59500).acceptPosition)
    }

    @Test
    fun `reset clears all state`() {
        coordinator.requestSeek(8000)
        now += 100
        coordinator.requestSeek(13000)
        coordinator.reset()
        assertTrue(coordinator.onPositionReported(500).acceptPosition)
        assertEquals(NONE, coordinator.flushQueued())
    }
}
