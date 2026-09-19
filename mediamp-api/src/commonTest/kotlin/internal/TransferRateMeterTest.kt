/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(InternalMediampApi::class)

package org.openani.mediamp.internal

import org.openani.mediamp.InternalMediampApi
import kotlin.test.Test
import kotlin.test.assertEquals

class TransferRateMeterTest {
    @Test
    fun `first sample yields zero`() {
        val meter = TransferRateMeter(windowMillis = 2_000)
        assertEquals(0, meter.sample(totalBytes = 1_000, nowMillis = 0))
    }

    @Test
    fun `rate is bytes per second between samples`() {
        val meter = TransferRateMeter(windowMillis = 2_000)
        meter.sample(totalBytes = 0, nowMillis = 0)
        assertEquals(1_000, meter.sample(totalBytes = 500, nowMillis = 500))
        assertEquals(1_000, meter.sample(totalBytes = 1_000, nowMillis = 1_000))
    }

    @Test
    fun `rate is averaged over the window`() {
        val meter = TransferRateMeter(windowMillis = 2_000)
        meter.sample(totalBytes = 0, nowMillis = 0)
        meter.sample(totalBytes = 2_000, nowMillis = 1_000) // 2000 B/s
        // 0 bytes in the second half: average over the 2 s window is 1000 B/s.
        assertEquals(1_000, meter.sample(totalBytes = 2_000, nowMillis = 2_000))
    }

    @Test
    fun `samples older than the window are dropped`() {
        val meter = TransferRateMeter(windowMillis = 2_000)
        meter.sample(totalBytes = 0, nowMillis = 0)
        meter.sample(totalBytes = 10_000, nowMillis = 1_000)
        meter.sample(totalBytes = 10_000, nowMillis = 2_000)
        meter.sample(totalBytes = 10_000, nowMillis = 3_000)
        // The window start is the newest sample at least 2 s old (t=2000); nothing was
        // transferred since, so the rate is 0 even though 10_000 bytes arrived at t=1000.
        assertEquals(0, meter.sample(totalBytes = 10_000, nowMillis = 4_000))
        // A burst of 4000 bytes in the last second, measured over the window start at t=3000.
        assertEquals(2_000, meter.sample(totalBytes = 14_000, nowMillis = 5_000))
    }

    @Test
    fun `counter going backwards yields zero`() {
        val meter = TransferRateMeter(windowMillis = 2_000)
        meter.sample(totalBytes = 5_000, nowMillis = 0)
        assertEquals(0, meter.sample(totalBytes = 1_000, nowMillis = 1_000))
    }

    @Test
    fun `same timestamp yields zero instead of dividing by zero`() {
        val meter = TransferRateMeter(windowMillis = 2_000)
        meter.sample(totalBytes = 0, nowMillis = 100)
        assertEquals(0, meter.sample(totalBytes = 1_000, nowMillis = 100))
    }

    @Test
    fun `reset forgets history`() {
        val meter = TransferRateMeter(windowMillis = 2_000)
        meter.sample(totalBytes = 0, nowMillis = 0)
        meter.sample(totalBytes = 1_000, nowMillis = 1_000)
        meter.reset()
        assertEquals(0, meter.sample(totalBytes = 0, nowMillis = 2_000))
        assertEquals(500, meter.sample(totalBytes = 500, nowMillis = 3_000))
    }
}
