/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.internal

import org.openani.mediamp.InternalMediampApi

/**
 * Computes a transfer rate from periodic samples of a cumulative byte counter.
 *
 * Each [sample] records the counter value at a point in time and returns the average rate over
 * the samples within the last [windowMillis]. Backends whose engine exposes only a cumulative
 * byte count (rather than a rate) feed it from their existing poll.
 *
 * Not thread-safe: call [sample] and [reset] from one thread.
 *
 * @param windowMillis length of the averaging window. Samples older than this are dropped once
 *   a newer sample can serve as the window start.
 */
@InternalMediampApi
public class TransferRateMeter(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private val sampleTimes = ArrayDeque<Long>()
    private val sampleTotals = ArrayDeque<Long>()

    /**
     * Records that [totalBytes] have been transferred in total as of [nowMillis], and returns the
     * average rate in bytes per second over the current window. Returns `0` until at least two
     * samples with distinct times have been recorded, and `0` if the counter went backwards.
     */
    public fun sample(totalBytes: Long, nowMillis: Long): Long {
        sampleTimes.addLast(nowMillis)
        sampleTotals.addLast(totalBytes)
        // Keep the newest sample that is at least windowMillis old as the window start, so the
        // measured span never shrinks below the window once enough history exists.
        while (sampleTimes.size >= 2 && nowMillis - sampleTimes[1] >= windowMillis) {
            sampleTimes.removeFirst()
            sampleTotals.removeFirst()
        }
        val elapsedMillis = nowMillis - sampleTimes.first()
        if (elapsedMillis <= 0) return 0
        val bytes = totalBytes - sampleTotals.first()
        if (bytes <= 0) return 0
        return bytes * 1000 / elapsedMillis
    }

    /**
     * Forgets all samples, e.g. when the counter restarts for a new media.
     */
    public fun reset() {
        sampleTimes.clear()
        sampleTotals.clear()
    }

    public companion object {
        public const val DEFAULT_WINDOW_MILLIS: Long = 2_000L
    }
}
