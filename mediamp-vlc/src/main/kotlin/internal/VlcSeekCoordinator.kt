/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.vlc.internal

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

/**
 * Throttles seeks sent to VLC and gates stale position reports while seeks are settling.
 *
 * Why: `libvlc_media_player_set_time` is asynchronous. On long-GOP media the demuxer takes
 * hundreds of milliseconds to land; meanwhile VLC's clock first jumps to the requested time
 * (emitting an immediate "echo" event) and is later *pulled back* when the demuxer actually
 * lands. Submitting another seek inside that window additionally breaks the decoder with
 * "no reference clock" errors (open-ani/animeko#1238). Users see the progress bar being
 * pulled back to stale positions during rapid fast-forwarding, and rapid skips being lost.
 *
 * Strategy — no reliance on VLC confirming anything:
 * - **Throttle**: at most one native seek per [minSubmitIntervalMillis]. The first request
 *   submits immediately (leading edge); requests inside the interval replace a queued target
 *   (latest wins) which the caller flushes via [flushQueued] after a delay (trailing edge),
 *   so the final target always reaches VLC.
 * - **Gate**: for [settleWindowMillis] after each submission, a position report is published
 *   only if it is within [acceptToleranceMillis] of the *latest intent* (queued target if any,
 *   else the last submitted target). Everything else — pre-seek positions, clock echoes of
 *   superseded targets, demuxer pull-backs — is dropped.
 * - **Retry**: if the window expires while VLC is still reporting positions far from the
 *   intent, VLC is likely still chewing through the (serialized) seeks of a rapid burst —
 *   the intent is resubmitted and the window renewed, up to [maxRetries] times. Only after
 *   retries are exhausted does the gate open fully, so a genuinely failed seek still
 *   re-converges to VLC's real position instead of holding a fake one forever.
 *
 * The player keeps [org.openani.mediamp.MediampPlayer.currentPositionMillis] optimistically
 * updated at the latest intent, so `skip()` bases follow-up targets on it and rapid presses
 * accumulate exactly.
 *
 * Thread-safe; called from the vlcj event thread and the UI thread.
 */
internal class VlcSeekCoordinator(
    private val minSubmitIntervalMillis: Long = 250,
    private val settleWindowMillis: Long = 1_000,
    private val acceptToleranceMillis: Long = 2_000,
    private val maxRetries: Int = 2,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantLock()
    private var lastSubmittedTarget: Long = NONE
    private var lastSubmittedAt: Long = 0
    private var queuedTarget: Long = NONE
    private var retries: Int = 0

    val minSubmitInterval: Long get() = minSubmitIntervalMillis

    /** Returns the target to submit to the native player now, or [NONE] if it was queued. */
    fun requestSeek(targetMillis: Long): Long = lock.withLock {
        val now = clock()
        if (lastSubmittedTarget != NONE && now - lastSubmittedAt < minSubmitIntervalMillis) {
            queuedTarget = targetMillis
            NONE
        } else {
            submitLocked(targetMillis, now)
        }
    }

    /**
     * Returns the queued target if the throttle interval has elapsed, or [NONE].
     * Call after a `delay(minSubmitInterval)` whenever [requestSeek] returned [NONE].
     */
    fun flushQueued(): Long = lock.withLock {
        val now = clock()
        if (queuedTarget == NONE || now - lastSubmittedAt < minSubmitIntervalMillis) {
            NONE
        } else {
            submitLocked(queuedTarget, now)
        }
    }

    class Report(
        /** Whether the reported position may be published to the position flow. */
        @JvmField val acceptPosition: Boolean,
        /** A queued seek that must be submitted to the native player now, or [NONE]. */
        @JvmField val submitTarget: Long,
    )

    fun onPositionReported(reportedMillis: Long): Report = lock.withLock {
        if (lastSubmittedTarget == NONE) return ACCEPT
        val now = clock()

        // opportunistic flush in case the caller's delayed flush was lost
        val submit = if (queuedTarget != NONE && now - lastSubmittedAt >= minSubmitIntervalMillis) {
            submitLocked(queuedTarget, now)
        } else NONE

        val intent = if (queuedTarget != NONE) queuedTarget else lastSubmittedTarget
        val nearIntent = abs(reportedMillis - intent) <= acceptToleranceMillis
        if (now - lastSubmittedAt <= settleWindowMillis) {
            return if (submit != NONE) Report(nearIntent, submit) else if (nearIntent) ACCEPT else DROP
        }
        // window expired
        if (nearIntent) {
            lastSubmittedTarget = NONE // settled: stop gating entirely
            return if (submit != NONE) Report(true, submit) else ACCEPT
        }
        if (retries < maxRetries) {
            // VLC is still far from the intent — likely still working through a rapid burst
            // of seeks. Push the intent again and keep holding the optimistic position.
            retries++
            return Report(acceptPosition = false, submitTarget = submitLocked(intent, now, isRetry = true))
        }
        lastSubmittedTarget = NONE // gave up: follow VLC's real position again
        if (submit != NONE) Report(true, submit) else ACCEPT
    }

    fun reset(): Unit = lock.withLock {
        lastSubmittedTarget = NONE
        queuedTarget = NONE
        retries = 0
    }

    private fun submitLocked(targetMillis: Long, now: Long, isRetry: Boolean = false): Long {
        lastSubmittedTarget = targetMillis
        lastSubmittedAt = now
        queuedTarget = NONE
        if (!isRetry) retries = 0 // a fresh user intent gets its full retry budget
        return targetMillis
    }

    companion object {
        const val NONE: Long = Long.MIN_VALUE
        private val ACCEPT = Report(acceptPosition = true, submitTarget = NONE)
        private val DROP = Report(acceptPosition = false, submitTarget = NONE)
    }
}
