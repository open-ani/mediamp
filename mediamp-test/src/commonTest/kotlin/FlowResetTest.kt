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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.MediaLoadCancellationException
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.PlayerState
import org.openani.mediamp.metadata.MediaProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Coverage of the spec section 9 flow-reset table: each transition's side-flow values
 * (mediaData / position / mediaProperties), asserted with **before-state ordering** — every
 * [StateObservation] snapshots the side flows at the instant of the state emission, so an
 * observer waking on a `mediaStatus` change never reads stale side flows.
 */
class FlowResetTest {

    private fun TestScope.createPlayer(): TestMediampPlayer =
        TestMediampPlayer(StandardTestDispatcher(testScheduler))

    private fun StateRecord.observationOf(status: MediaStatus): StateObservation =
        assertNotNull(observations.firstOrNull { it.state.mediaStatus == status }, "no emission with status $status")

    @Test
    fun `stop transition resets mediaData position and properties`(): TestResult = runTest {
        // regression: T3 (in v1 the fake cleared flows before emitting FINISHED; in v2 the
        // Idle row's implied values ARE the reset values, written before the emission)
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data, playWhenReady = true, startPositionMillis = 10_000L)
        advanceUntilIdle()
        player.injectPosition(42_000L)
        advanceUntilIdle()
        val log = recordStatesOf(player)

        player.stopPlayback()
        advanceUntilIdle()

        val idle = log.observationOf(MediaStatus.Idle)
        assertNull(idle.mediaData)
        assertEquals(0L, idle.positionMillis)
        assertNull(idle.properties)
        assertEquals(1, data.closeCalls)
        player.close()
    }

    @Test
    fun `Opening emission carries the optimistic start position and null side flows`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStatesOf(player)
        val hold = TestMediampPlayer.OpenBehavior.Hold()
        player.openBehavior = hold
        val data = TrackingMediaData()

        val call = async { player.setMediaData(data, playWhenReady = true, startPositionMillis = 10_000L) }
        advanceUntilIdle()

        val opening = log.observationOf(MediaStatus.Opening)
        assertNull(opening.mediaData) // not installed until the Ready point
        assertEquals(10_000L, opening.positionMillis) // optimistic
        assertNull(opening.properties)

        hold.release()
        advanceUntilIdle()
        call.await()

        // Opening -> Ready row: mediaData set BEFORE the status emission, position kept,
        // properties as known at the Ready point.
        val ready = log.observationOf(MediaStatus.Ready)
        assertSame(data, ready.mediaData)
        assertEquals(10_000L, ready.positionMillis)
        assertNotNull(ready.properties)
        player.close()
    }

    @Test
    fun `supersession keeps Opening with the new start position and params`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStatesOf(player)
        val first = TrackingMediaData("test://first")
        val second = TrackingMediaData("test://second")

        player.openBehavior = TestMediampPlayer.OpenBehavior.Hold()
        val firstCall = async { player.setMediaData(first, playWhenReady = false, startPositionMillis = 1_000L) }
        advanceUntilIdle()

        player.openBehavior = TestMediampPlayer.OpenBehavior.Hold()
        val secondCall = async { player.setMediaData(second, playWhenReady = true, startPositionMillis = 2_000L) }
        advanceUntilIdle()
        assertFailsWith<MediaLoadCancellationException> { firstCall.await() }

        // Section 9 supersession row: mediaData stays null, new startPosition, new param.
        val secondOpening = assertNotNull(
            log.observations.lastOrNull { it.state.mediaStatus == MediaStatus.Opening },
        )
        assertEquals(playerState(MediaStatus.Opening, playWhenReady = true), secondOpening.state)
        assertNull(secondOpening.mediaData)
        assertEquals(2_000L, secondOpening.positionMillis)
        assertNull(secondOpening.properties)

        secondCall.cancel()
        advanceUntilIdle()
        player.close()
    }

    @Test
    fun `Ended emission retains mediaData and pins position to the duration`(): TestResult = runTest {
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data, playWhenReady = true)
        advanceUntilIdle()
        player.injectPosition(97_000L)
        advanceUntilIdle()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.injectEnded()
        advanceUntilIdle()

        val ended = log.observationOf(MediaStatus.Ended)
        assertSame(data, ended.mediaData) // retained
        assertEquals(100_000L, ended.positionMillis) // == duration when known
        assertNotNull(ended.properties) // retained

        // The MediaEnded event carries the facts, immune to a fast reactor resetting the flows.
        val event = assertNotNull(events.ofType<PlaybackEvent.MediaEnded>().singleOrNull())
        assertSame(data, event.mediaData)
        assertEquals(100_000L, event.finalPositionMillis)
        assertEquals(100_000L, event.durationMillis)
        player.close()
    }

    @Test
    fun `Ended with unknown duration retains the last observed position`(): TestResult = runTest {
        val player = createPlayer()
        player.defaultMediaProperties = MediaProperties(title = "Live", durationMillis = null)
        val data = TrackingMediaData()
        player.setMediaData(data, playWhenReady = true)
        advanceUntilIdle()
        player.injectPosition(42_000L)
        advanceUntilIdle()
        val events = recordEventsOf(player)

        player.injectEnded()
        advanceUntilIdle()

        assertEquals(MediaStatus.Ended, player.state.value.mediaStatus)
        assertEquals(42_000L, player.currentPositionMillis.value) // no duration to pin to
        val event = assertNotNull(events.ofType<PlaybackEvent.MediaEnded>().singleOrNull())
        assertEquals(42_000L, event.finalPositionMillis)
        assertNull(event.durationMillis)
        player.close()
    }

    @Test
    fun `Error emission clears mediaData position and properties`(): TestResult = runTest {
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data, playWhenReady = true, startPositionMillis = 30_000L)
        advanceUntilIdle()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)
        val error = PlaybackException(PlaybackErrorCode.DECODING, "boom")

        player.injectError(error)
        advanceUntilIdle()

        val errorObservation = assertNotNull(
            log.observations.firstOrNull { it.state.mediaStatus is MediaStatus.Error },
        )
        assertNull(errorObservation.mediaData) // released and cleared BEFORE the Error emission
        assertEquals(0L, errorObservation.positionMillis)
        assertNull(errorObservation.properties)
        assertEquals(listOf(error), events.ofType<PlaybackEvent.ErrorOccurred>().map { it.error })
        assertEquals(1, data.closeCalls)
        player.close()
    }

    @Test
    fun `Released emission clears all side flows and nothing emits after`(): TestResult = runTest {
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data, playWhenReady = true, startPositionMillis = 5_000L)
        advanceUntilIdle()
        val log = recordStatesOf(player)

        player.close()
        advanceUntilIdle()

        val released = log.observationOf(MediaStatus.Released)
        assertNull(released.mediaData)
        assertEquals(0L, released.positionMillis)
        assertNull(released.properties)

        val count = log.states.size
        player.injectStall(true)
        player.injectPosition(1L)
        player.injectEnded()
        advanceUntilIdle()
        assertEquals(count, log.states.size) // I3: no flow ever emits after Released
    }

    @Test
    fun `public flows are read-only`(): TestResult = runTest {
        // regression: C11 (v1 exposed playbackState as a public MutableStateFlow — clients and
        // tests could bypass every guard; v2 test injection goes through the scripting surface)
        val player = createPlayer()
        assertFalse(player.state is MutableStateFlow<*>)
        assertFalse(player.events is MutableSharedFlow<*>)
        assertFalse(player.mediaData is MutableStateFlow<*>)
        assertFalse(player.mediaProperties is MutableStateFlow<*>)
        assertFalse(player.currentPositionMillis is MutableStateFlow<*>)
        @Suppress("DEPRECATION")
        assertFalse(player.playbackState is MutableStateFlow<*>)
        player.close()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy playbackState derivation pins the v1-exo latch semantics`(): TestResult = runTest {
        // regression: C12 (the v1 doc contradictions around READY/BUFFERING are replaced by an
        // executable mapping: READY until first isPlaying, then PLAYING/PAUSED/PAUSED_BUFFERING)
        val player = createPlayer()
        player.setMediaData(TrackingMediaData()) // paused open
        advanceUntilIdle()
        assertEquals(org.openani.mediamp.PlaybackState.READY, player.playbackState.value)

        player.injectStall(true)
        advanceUntilIdle()
        player.play()
        // Initial buffering window before the first frame: still READY (v1-exo suppression).
        assertEquals(org.openani.mediamp.PlaybackState.READY, player.playbackState.value)

        player.injectStall(false)
        advanceUntilIdle()
        assertEquals(org.openani.mediamp.PlaybackState.PLAYING, player.playbackState.value)

        player.injectStall(true) // played latch flipped: mid-play stall is PAUSED_BUFFERING
        advanceUntilIdle()
        assertEquals(org.openani.mediamp.PlaybackState.PAUSED_BUFFERING, player.playbackState.value)

        player.pause() // intent wins over buffering in the legacy mapping
        assertEquals(org.openani.mediamp.PlaybackState.PAUSED, player.playbackState.value)

        val status = player.state.value
        assertTrue(status.isBuffering) // the v2 axes stay orthogonal underneath
        assertFalse(status.playWhenReady)
        player.close()
    }

    @Test
    fun `error status carries the typed cause`(): TestResult = runTest {
        // regression: C7 (v1 ERROR carried no information at all)
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        advanceUntilIdle()
        val cause = RuntimeException("root cause")
        val error = PlaybackException(PlaybackErrorCode.ACCESS_DENIED, "drm denied", cause)

        player.injectError(error)
        advanceUntilIdle()

        val status = assertIs<MediaStatus.Error>(player.state.value.mediaStatus)
        assertSame(error, status.error)
        assertEquals(PlaybackErrorCode.ACCESS_DENIED, status.error.code)
        assertEquals("drm denied", status.error.message)
        assertSame(cause, status.error.cause)
        player.close()
    }
}
