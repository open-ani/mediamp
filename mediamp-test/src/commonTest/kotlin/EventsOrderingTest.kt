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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.PlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Coverage of the spec section 5 ordering rules for [org.openani.mediamp.MediampPlayer.events]:
 * events are delivered **after** the `state` commit of their transition (an immediate-style
 * collector always observes post-transition state), and edges are delivered losslessly to every
 * subscribed collector — even when a fast reactor advances the session on receipt (the
 * conflation hazard that motivated the events channel).
 */
class EventsOrderingTest {

    private fun TestScope.createPlayer(): TestMediampPlayer =
        TestMediampPlayer(StandardTestDispatcher(testScheduler))

    /** Records each event together with the [PlayerState] observed at the instant of receipt. */
    private class EventWithState(val event: PlaybackEvent, val stateAtReceipt: PlayerState)

    private fun TestScope.recordEventsWithState(player: TestMediampPlayer): MutableList<EventWithState> {
        val record = mutableListOf<EventWithState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.events.collect { record += EventWithState(it, player.state.value) }
        }
        return record
    }

    @Test
    fun `MediaEnded is delivered after the Ended state commit`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        advanceUntilIdle()
        val record = recordEventsWithState(player)

        player.injectEnded()
        advanceUntilIdle()

        val observed = assertNotNull(record.singleOrNull { it.event is PlaybackEvent.MediaEnded })
        // The collector observes the post-transition snapshot, I2 included.
        assertEquals(playerState(MediaStatus.Ended), observed.stateAtReceipt)
        player.close()
    }

    @Test
    fun `ErrorOccurred is delivered after the Error state commit`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        advanceUntilIdle()
        val record = recordEventsWithState(player)
        val error = PlaybackException(PlaybackErrorCode.IO, "boom")

        player.injectError(error)
        advanceUntilIdle()

        val observed = assertNotNull(record.singleOrNull { it.event is PlaybackEvent.ErrorOccurred })
        assertEquals(playerState(MediaStatus.Error(error)), observed.stateAtReceipt)
        assertSame(error, (observed.event as PlaybackEvent.ErrorOccurred).error)
        player.close()
    }

    @Test
    fun `SeekCompleted is delivered after the position commit`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData())
        advanceUntilIdle()
        val positionsAtReceipt = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.events.collect {
                if (it is PlaybackEvent.SeekCompleted) {
                    positionsAtReceipt += player.currentPositionMillis.value
                }
            }
        }

        player.seekTo(20_000L)
        advanceUntilIdle()

        assertEquals(listOf(20_000L), positionsAtReceipt) // position already committed at receipt
        player.close()
    }

    @Test
    fun `ExternalPlayWhenReadyChanged reflects the already-adopted state`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData())
        advanceUntilIdle()
        val record = recordEventsWithState(player)

        player.injectExternalPlayWhenReady(true)
        advanceUntilIdle()

        val observed = assertNotNull(
            record.singleOrNull { it.event is PlaybackEvent.ExternalPlayWhenReadyChanged },
        )
        val event = assertIs<PlaybackEvent.ExternalPlayWhenReadyChanged>(observed.event)
        assertTrue(event.value)
        assertEquals(event.value, observed.stateAtReceipt.playWhenReady) // state adopted first
        player.close()
    }

    @Test
    fun `MediaEnded reaches both collectors when the first replays immediately`(): TestResult = runTest {
        // The conflation hazard (spec section 5 Ordering): a fast reactor moves the state on
        // during delivery; `state` alone would conflate the Ended edge away from collector 2.
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data, playWhenReady = true)
        advanceUntilIdle()

        val firstReceived = mutableListOf<PlaybackEvent.MediaEnded>()
        val secondReceived = mutableListOf<EventWithState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.events.collect {
                if (it is PlaybackEvent.MediaEnded) {
                    firstReceived += it
                    player.play() // fast reactor: replay synchronously on receipt (re-entrant command)
                }
            }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.events.collect {
                if (it is PlaybackEvent.MediaEnded) secondReceived += EventWithState(it, player.state.value)
            }
        }

        player.injectEnded()
        advanceUntilIdle()

        assertEquals(1, firstReceived.size)
        assertEquals(1, secondReceived.size) // the edge is NOT lost to the second collector
        // The second collector observes a committed post-transition snapshot: either still
        // Ended, or already the replayed Ready — never the pre-Ended state (spec section 5:
        // later collectors of the same event may observe the newer state).
        val late = secondReceived.single()
        assertTrue(
            late.stateAtReceipt == playerState(MediaStatus.Ended) ||
                (late.stateAtReceipt == playerState(MediaStatus.Ready, playWhenReady = true) &&
                    player.currentPositionMillis.value == 0L),
            "unexpected snapshot at receipt: ${late.stateAtReceipt}",
        )
        assertTrue(player.state.value.isPlaying) // the replay took effect
        assertEquals(1, player.openCallCount) // replay did not reopen
        player.close()
    }

    @Test
    fun `MediaEnded reaches both collectors when the first starts the next media`(): TestResult = runTest {
        // regression: C1 (auto-next via setMediaData from Ended releases the finished media
        // exactly once) — and the animeko auto-next pattern: react to events, never to the
        // conflated state.
        val player = createPlayer()
        val finished = TrackingMediaData("test://finished")
        val next = TrackingMediaData("test://next")
        player.setMediaData(finished, playWhenReady = true)
        advanceUntilIdle()

        val firstReceived = mutableListOf<PlaybackEvent.MediaEnded>()
        val secondReceived = mutableListOf<PlaybackEvent.MediaEnded>()
        val testScope = this // auto-next runs as a foreground test coroutine
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.events.collect { event ->
                if (event is PlaybackEvent.MediaEnded) {
                    firstReceived += event
                    // Auto-next: advance the session in reaction to the edge.
                    testScope.launch { player.setMediaData(next, playWhenReady = true) }
                }
            }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.events.collect { if (it is PlaybackEvent.MediaEnded) secondReceived += it }
        }

        player.injectEnded()
        advanceUntilIdle()

        assertEquals(1, firstReceived.size)
        assertEquals(1, secondReceived.size) // both observed the edge despite the fast reactor
        assertSame(finished, firstReceived.single().mediaData)
        assertEquals(100_000L, firstReceived.single().durationMillis) // facts travel on the event

        assertSame(next, player.mediaData.value) // the auto-next landed
        assertTrue(player.state.value.isPlaying)
        assertEquals(1, finished.closeCalls) // the finished media was released exactly once
        assertFalse(next.closed)
        player.close()
    }
}
