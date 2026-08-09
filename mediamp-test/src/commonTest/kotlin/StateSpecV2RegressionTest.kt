/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalMediampApi::class)

package org.openani.mediamp.test

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.MediaLoadCancellationException
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.PlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression tests for the defects found in the state-spec-v2 adversarial review: the
 * open-cancellation family (hangs/leaks around not-yet-started opens), commit() re-entrancy,
 * the open-handoff snapshot fold, seekTo-during-Opening convergence, and the
 * SeekCompleted-after-state-commit ordering rule.
 */
class StateSpecV2RegressionTest {

    // region open-cancellation family

    @Suppress("DEPRECATION")
    @Test
    fun `rapid double setMediaData neither hangs the first caller nor leaks its data`(): TestResult = runTest {
        // regression: runOpen launched with a cancellable start could be cancelled before its
        // body ever ran — the first caller then awaited a completion nobody would ever settle,
        // and the MediaData was never closed.
        val main = StandardTestDispatcher(testScheduler)
        val player = TestMediampPlayer(main)
        val first = TrackingMediaData("test://first")
        val second = TrackingMediaData("test://second")

        // Same-dispatcher callers: each entry runs inline in its task, so the second entry
        // cancels the first attempt's job before the first runOpen task ever starts.
        val firstCall = async(main) { player.setMediaData(first) }
        val secondCall = async(main) { player.setMediaData(second) }
        advanceUntilIdle()

        assertFailsWith<MediaLoadCancellationException> { firstCall.await() }
        secondCall.await()
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
        assertSame(second, player.mediaData.value)
        assertEquals(1, first.closeCalls) // released exactly once, not leaked
        assertFalse(second.closed)
        player.close()
    }

    @Test
    fun `stopPlayback queued before the open coroutine starts releases the data and fails the caller`(): TestResult =
        runTest {
            // regression: same window as above, hit by stopPlayback instead of supersession.
            val main = StandardTestDispatcher(testScheduler)
            val player = TestMediampPlayer(main)
            val data = TrackingMediaData()

            val caller = async(main) { player.setMediaData(data) }
            launch(main) { player.stopPlayback() } // queued behind the entry, before runOpen
            advanceUntilIdle()

            assertFailsWith<MediaLoadCancellationException> { caller.await() }
            assertEquals(PlayerState.Initial, player.state.value)
            assertEquals(1, data.closeCalls)
            assertEquals(0, player.openCallCount) // openImpl never ran for the aborted attempt
            player.close()
        }

    @Test
    fun `caller cancelled before the entry block runs still releases the data`(): TestResult = runTest {
        // regression: the main-dispatcher entry hop was outside the ownership guard — a
        // cancelled caller whose entry never ran left the data neither installed nor
        // released (spec §3: ownership transfers at call entry, unconditionally).
        val player = TestMediampPlayer(StandardTestDispatcher(testScheduler))
        val data = TrackingMediaData()

        val caller = launch {
            coroutineContext[Job]!!.cancel() // cancelled at call entry
            var cancelled = false
            try {
                player.setMediaData(data) // entry hop never runs its block
            } catch (e: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
        }
        advanceUntilIdle()

        assertTrue(caller.isCancelled)
        assertEquals(1, data.closeCalls) // ownership at call entry: released exactly once
        assertEquals(0, player.openCallCount)
        assertEquals(MediaStatus.Idle, player.state.value.mediaStatus)
        player.close()
    }
    // endregion

    // region commit() re-entrancy

    @Suppress("DEPRECATION")
    @Test
    fun `re-entrant close from a collector cannot emit events after Released or desync the legacy flow`(): TestResult =
        runTest {
            // regression: a synchronously-resumed state collector calling close() mid-commit
            // let the outer commit deliver MediaEnded after Released (I3 violation) and
            // overwrite the legacy flow with FINISHED after DESTROYED.
            val player = TestMediampPlayer(StandardTestDispatcher(testScheduler))
            val statusAtMediaEnded = mutableListOf<MediaStatus>()
            val legacyLog = mutableListOf<PlaybackState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                player.events.collect {
                    if (it is PlaybackEvent.MediaEnded) statusAtMediaEnded += player.state.value.mediaStatus
                }
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                @Suppress("DEPRECATION")
                player.playbackState.collect { legacyLog += it }
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                player.state.collect { if (it.mediaStatus == MediaStatus.Ended) player.close() }
            }

            player.setMediaData(TrackingMediaData(), playWhenReady = true)
            advanceUntilIdle()
            player.injectEnded()
            advanceUntilIdle()

            assertEquals(MediaStatus.Released, player.state.value.mediaStatus)
            // The MediaEnded event was delivered before Released committed (I3):
            assertEquals(listOf<MediaStatus>(MediaStatus.Ended), statusAtMediaEnded)
            // The legacy flow ends at DESTROYED — no FINISHED-after-DESTROYED desync:
            assertEquals(PlaybackState.DESTROYED, legacyLog.last())
        }

    @Test
    fun `re-entrant play on MediaEnded starts a replay after the Ended transition completes`(): TestResult = runTest {
        // The spec-sanctioned auto-next-style reaction: a command issued from an events
        // collector during the Ended transition is queued and runs right after it.
        val player = TestMediampPlayer(StandardTestDispatcher(testScheduler))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.events.collect { if (it is PlaybackEvent.MediaEnded) player.play() }
        }

        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        advanceUntilIdle()
        val opensBefore = player.openCallCount
        player.injectEnded()
        advanceUntilIdle()

        // Replay (spec §3): same session, playing again from the start — no re-open.
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
        assertTrue(player.state.value.playWhenReady)
        assertEquals(opensBefore, player.openCallCount)
        player.close()
    }
    // endregion

    // region open handoff fold

    @Suppress("DEPRECATION")
    @Test
    fun `autoplay open still prefetching commits Ready-buffering in its first emission`(): TestResult = runTest {
        // regression: installSession committed Ready with isBuffering=false and only then
        // processed the handoff snapshot — fabricating a transient isPlaying and flipping the
        // legacy neverPlayed latch during initial buffering (pinned as READY, spec §2).
        val player = TestMediampPlayer(StandardTestDispatcher(testScheduler))
        player.openInitiallyStalled = true
        val record = recordStatesOf(player)

        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        advanceUntilIdle()

        val readyStates = record.states.filter { it.mediaStatus == MediaStatus.Ready }
        assertEquals(
            playerState(MediaStatus.Ready, playWhenReady = true, isBuffering = true),
            readyStates.first(), // the data axis arrives with the first Ready emission
        )
        assertTrue(record.states.none { it.isPlaying }) // no fabricated isPlaying glitch
        assertEquals(PlaybackState.READY, player.playbackState.value) // latch intact

        // Buffering clears -> genuinely playing; only now does the legacy latch flip.
        player.injectStall(false)
        advanceUntilIdle()
        assertTrue(player.state.value.isPlaying)
        assertEquals(PlaybackState.PLAYING, player.playbackState.value)
        player.close()
    }
    // endregion

    // region seekTo during Opening

    @Test
    fun `seekTo during a running open converges natively after Ready`(): TestResult = runTest {
        // regression: a seek landing while openImpl was already running only updated the
        // attempt's field — the backend kept the stale start position and the first position
        // report snapped the public position back.
        val player = TestMediampPlayer(StandardTestDispatcher(testScheduler))
        val hold = TestMediampPlayer.OpenBehavior.Hold()
        player.openBehavior = hold
        val data = TrackingMediaData()

        val caller = launch { player.setMediaData(data) } // startPositionMillis = 0
        advanceUntilIdle()
        assertEquals(MediaStatus.Opening, player.state.value.mediaStatus)

        player.seekTo(30_000)
        assertEquals(30_000, player.currentPositionMillis.value) // optimistic emission

        hold.release()
        advanceUntilIdle()
        caller.join()

        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
        assertEquals(30_000, player.nativePositionMillis) // the compensation seek reached the backend
        assertEquals(30_000, player.currentPositionMillis.value) // no snap-back
        player.close()
    }
    // endregion

    // region SeekCompleted ordering

    @Test
    fun `SeekCompleted collectors observe the completion snapshot's state`(): TestResult = runTest {
        // regression: the SeekCompleted event was emitted before the completion-snapshot
        // state commit — a collector reading state.value on receipt saw the pre-seek data
        // axis, violating the events-after-state rule (spec §5/§9).
        val player = TestMediampPlayer(StandardTestDispatcher(testScheduler))
        player.setMediaData(TrackingMediaData()) // paused
        advanceUntilIdle()

        var bufferingAtSeekCompleted: Boolean? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.events.collect {
                if (it is PlaybackEvent.SeekCompleted) {
                    bufferingAtSeekCompleted = player.state.value.isBuffering
                }
            }
        }

        player.holdSeeks = true
        player.seekTo(50_000)
        advanceUntilIdle()
        player.completeHeldSeek(stalledAtCompletion = true) // seek landed in an unbuffered region
        advanceUntilIdle()

        assertEquals(true, bufferingAtSeekCompleted) // post-transition state, as the spec promises
        assertTrue(player.state.value.isBuffering)
        player.close()
    }
    // endregion
}
