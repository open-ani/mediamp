/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalMediampApi::class)

package org.openani.mediamp.test

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage of the spec section 5 intent-reconciliation rules: desired-vs-observed
 * classification on every transport report, read-after-command echoes, external-change
 * adoption, refusal, and the bounded retry budget. There are no expectation queues — every
 * scenario asserts the *absence* of spurious events and the boundedness of native commands.
 */
class IntentReconciliationTest {

    private fun TestScope.createPlayer(): TestMediampPlayer =
        TestMediampPlayer(StandardTestDispatcher(testScheduler))

    @Test
    fun `read-after-command echo is consumed silently`(): TestResult = runTest {
        // regression: C5-class (v1 latches produced stale-expectation desync; v2 echoes are
        // classified against desired and never surface as events or extra emissions)
        val player = createPlayer()
        player.setMediaData(TrackingMediaData())
        advanceUntilIdle()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.play()
        advanceUntilIdle() // playImpl's read-after-command report drains here

        assertEquals(
            listOf(
                playerState(MediaStatus.Ready),
                playerState(MediaStatus.Ready, playWhenReady = true),
            ),
            log.states, // exactly one emission: the synchronous intent flip; the echo is silent
        )
        assertTrue(events.events.isEmpty()) // in particular no ExternalPlayWhenReadyChanged
        assertEquals(1, player.playCallCount)
        player.close()
    }

    @Test
    fun `external change is adopted and published without commanding the backend`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData()) // paused; native transport level false
        advanceUntilIdle()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.injectExternalPlayWhenReady(true) // e.g. a media-session play command
        advanceUntilIdle()

        assertEquals(
            listOf(
                playerState(MediaStatus.Ready),
                playerState(MediaStatus.Ready, playWhenReady = true),
            ),
            log.states,
        )
        assertEquals(
            listOf(true),
            events.ofType<PlaybackEvent.ExternalPlayWhenReadyChanged>().map { it.value },
        )
        // Adoption never commands the backend and never creates expectations (spec section 5).
        assertEquals(0, player.playCallCount)
        assertEquals(0, player.pauseCallCount)
        player.close()
    }

    @Test
    fun `refused play is adopted immediately with no retry`(): TestResult = runTest {
        // regression: W1-class (v1 stranded PLAYING forever on autoplay rejection)
        val player = createPlayer()
        player.setMediaData(TrackingMediaData())
        advanceUntilIdle()

        player.play()
        advanceUntilIdle()
        assertTrue(player.state.value.playWhenReady)
        assertEquals(1, player.playCallCount)
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        // The platform refuses playback (e.g. browser autoplay policy).
        player.injectExternalPlayWhenReady(false, refused = true)
        advanceUntilIdle()

        assertEquals(
            listOf(
                playerState(MediaStatus.Ready, playWhenReady = true),
                playerState(MediaStatus.Ready), // adopted false
            ),
            log.states,
        )
        assertEquals(
            listOf(false),
            events.ofType<PlaybackEvent.ExternalPlayWhenReadyChanged>().map { it.value },
        )
        assertEquals(1, player.playCallCount) // no retry storm: refusal short-circuits the budget
        player.close()
    }

    @Test
    fun `retry budget exhaustion adopts the observed level`(): TestResult = runTest {
        // A platform that silently ignores intent commands (reports the unchanged level without
        // a refusal flag): the machine re-commands within the budget of 2, then adopts.
        val player = createPlayer()
        player.setMediaData(TrackingMediaData())
        advanceUntilIdle()
        player.ignoreIntentCommands = true
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.play()
        assertTrue(player.state.value.playWhenReady) // optimistic synchronous flip
        advanceUntilIdle()

        // 1 initial command + 2 budgeted re-commands, then adoption — bounded, no storm.
        assertEquals(3, player.playCallCount)
        assertFalse(player.state.value.playWhenReady)
        assertEquals(
            listOf(
                playerState(MediaStatus.Ready),
                playerState(MediaStatus.Ready, playWhenReady = true),
                playerState(MediaStatus.Ready), // budget exhausted: observed level adopted
            ),
            log.states,
        )
        assertEquals(
            listOf(false),
            events.ofType<PlaybackEvent.ExternalPlayWhenReadyChanged>().map { it.value },
        )
        player.close()
    }

    @Test
    fun `external change inside the applying window is fought once then converges`(): TestResult = runTest {
        // Spec section 5, known bounded tradeoff: an external change landing between a
        // machine-issued command and its echo is re-commanded once — the machine's intent wins.
        val player = createPlayer()
        player.setMediaData(TrackingMediaData())
        advanceUntilIdle()
        val events = recordEventsOf(player)

        player.play() // command issued; echo not yet drained
        player.injectExternalPlayWhenReady(false) // external flip lands inside the window
        advanceUntilIdle()

        assertTrue(player.state.value.playWhenReady) // machine re-commanded its desired value
        assertTrue(player.nativePlayWhenReady)
        assertEquals(2, player.playCallCount) // exactly one re-command, then convergence
        assertTrue(events.ofType<PlaybackEvent.ExternalPlayWhenReadyChanged>().isEmpty())
        player.close()
    }

    @Test
    fun `mid-open refusal adopts into the pending intent`(): TestResult = runTest {
        // Spec section 5: intent reports during Opening run through the same classification —
        // a refusal is adopted into the pending intent and published.
        val player = createPlayer()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)
        val hold = TestMediampPlayer.OpenBehavior.Hold()
        player.openBehavior = hold

        val call = async { player.setMediaData(TrackingMediaData(), playWhenReady = true) }
        advanceUntilIdle()
        assertEquals(playerState(MediaStatus.Opening, playWhenReady = true), player.state.value)

        player.injectExternalPlayWhenReady(false, refused = true)
        advanceUntilIdle()
        assertEquals(playerState(MediaStatus.Opening), player.state.value)
        assertEquals(
            listOf(false),
            events.ofType<PlaybackEvent.ExternalPlayWhenReadyChanged>().map { it.value },
        )

        hold.release()
        advanceUntilIdle()
        call.await()
        assertEquals(
            listOf(
                PlayerState.Initial,
                playerState(MediaStatus.Opening, playWhenReady = true),
                playerState(MediaStatus.Opening), // refusal adopted mid-open
                playerState(MediaStatus.Ready), // Ready commits with the adopted pending intent
            ),
            log.states,
        )
        assertFalse(player.nativePlayWhenReady) // handoff mismatch reconciled by pauseImpl
        player.close()
    }

    @Test
    fun `external pause at Ended is bookkeeping only`(): TestResult = runTest {
        // Spec section 5 matrix: transport reports at Ended never emit — an external play on
        // the ended screen does NOT implicitly replay.
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        player.injectEnded()
        advanceUntilIdle()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.injectExternalPlayWhenReady(true) // e.g. a lock-screen play at the ended screen
        advanceUntilIdle()

        assertEquals(listOf(playerState(MediaStatus.Ended)), log.states) // no implicit replay
        assertTrue(events.events.isEmpty())
        player.close()
    }
}
