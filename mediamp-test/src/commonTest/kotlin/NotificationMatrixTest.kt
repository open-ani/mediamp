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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.PlayerState
import org.openani.mediamp.metadata.MediaProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Systematic coverage of the spec section 5 notification x status matrix (machine side):
 * which backend facts act in which [MediaStatus], and which are dropped — including the
 * seek-in-flight gating window and invalidated-session drops.
 */
class NotificationMatrixTest {

    private fun TestScope.createPlayer(): TestMediampPlayer =
        TestMediampPlayer(StandardTestDispatcher(testScheduler))

    // region stall (reportTransport data axis): acts only in Ready (I1)

    @Test
    fun `injectStall at Idle is dropped`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStatesOf(player)

        player.injectStall(true)
        advanceUntilIdle()

        assertEquals(listOf(PlayerState.Initial), log.states)
        player.close()
    }

    @Test
    fun `injectStall during Opening is dropped`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStatesOf(player)
        val hold = TestMediampPlayer.OpenBehavior.Hold()
        player.openBehavior = hold
        val call = async { player.setMediaData(TrackingMediaData()) }
        advanceUntilIdle()

        player.injectStall(true) // I1: isBuffering can only be true in Ready
        advanceUntilIdle()
        assertEquals(playerState(MediaStatus.Opening), player.state.value)

        hold.release()
        advanceUntilIdle()
        call.await()

        assertEquals(
            listOf(
                PlayerState.Initial,
                playerState(MediaStatus.Opening),
                playerState(MediaStatus.Ready),
            ),
            log.states, // isBuffering never became true anywhere
        )
        player.close()
    }

    @Test
    fun `injectStall in Ready acts on the buffering axis`(): TestResult = runTest {
        // regression: R2/M9-class (post-load stalls are representable; spinner has one source)
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        advanceUntilIdle()
        val log = recordStatesOf(player)

        player.injectStall(true)
        advanceUntilIdle()
        player.injectStall(false)
        advanceUntilIdle()

        assertEquals(
            listOf(
                playerState(MediaStatus.Ready, playWhenReady = true),
                playerState(MediaStatus.Ready, playWhenReady = true, isBuffering = true),
                playerState(MediaStatus.Ready, playWhenReady = true),
            ),
            log.states,
        )
        player.close()
    }

    @Test
    fun `injectStall at Ended is dropped`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        player.injectEnded()
        advanceUntilIdle()
        val log = recordStatesOf(player)

        player.injectStall(true)
        advanceUntilIdle()

        assertEquals(listOf(playerState(MediaStatus.Ended)), log.states) // no emission, I1 held
        player.injectStall(false)
        player.close()
    }

    @Test
    fun `injectStall at Error is dropped`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        player.injectError(PlaybackException(PlaybackErrorCode.IO, "boom"))
        advanceUntilIdle()
        val log = recordStatesOf(player)

        player.injectStall(true)
        advanceUntilIdle()

        assertEquals(1, log.states.size) // only the initial Error snapshot
        assertIs<MediaStatus.Error>(player.state.value.mediaStatus)
        player.close()
    }

    @Test
    fun `injectStall after close is dropped`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        advanceUntilIdle()
        player.close()
        advanceUntilIdle()
        val log = recordStatesOf(player)

        player.injectStall(true) // I3: no flow of the player ever emits after Released
        advanceUntilIdle()

        assertEquals(listOf(playerState(MediaStatus.Released)), log.states)
    }
    // endregion

    // region notifyEnded: acts in Ready; dropped in Opening/Ended and while seek-in-flight

    @Test
    fun `injectEnded during Opening is dropped`(): TestResult = runTest {
        val player = createPlayer()
        val events = recordEventsOf(player)
        val hold = TestMediampPlayer.OpenBehavior.Hold()
        player.openBehavior = hold
        val call = async { player.setMediaData(TrackingMediaData()) }
        advanceUntilIdle()

        player.injectEnded()
        advanceUntilIdle()
        assertEquals(MediaStatus.Opening, player.state.value.mediaStatus) // still opening

        hold.release()
        advanceUntilIdle()
        call.await()

        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus) // not Ended
        assertTrue(events.ofType<PlaybackEvent.MediaEnded>().isEmpty())
        player.close()
    }

    @Test
    fun `injectEnded in Ready enters Ended`(): TestResult = runTest {
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data, playWhenReady = true)
        advanceUntilIdle()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.injectEnded()
        advanceUntilIdle()

        assertEquals(
            listOf(
                playerState(MediaStatus.Ready, playWhenReady = true),
                playerState(MediaStatus.Ended), // pwr normalized false in the same emission (I2)
            ),
            log.states,
        )
        assertSame(data, player.mediaData.value) // retained
        assertFalse(player.nativePlayWhenReady) // machine-issued pauseImpl on Ended entry (spec section 6)
        assertEquals(1, events.ofType<PlaybackEvent.MediaEnded>().size)
        player.close()
    }

    @Test
    fun `injectEnded at Ended is dropped`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        player.injectEnded()
        advanceUntilIdle()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.injectEnded() // duplicate end-of-media fact
        advanceUntilIdle()

        assertEquals(listOf(playerState(MediaStatus.Ended)), log.states)
        assertTrue(events.ofType<PlaybackEvent.MediaEnded>().isEmpty()) // no second MediaEnded
        player.close()
    }

    @Test
    fun `injectEnded while a seek is held is dropped as stale`(): TestResult = runTest {
        // regression: C4-class (a queued end-of-media fact from before the seek must die in the
        // gating window — e.g. mpv eof-reached delivered after a replay seek)
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        advanceUntilIdle()
        val events = recordEventsOf(player)
        player.holdSeeks = true

        player.seekTo(30_000L)
        player.injectEnded() // stale EOF lands inside the seek window
        advanceUntilIdle()
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)

        player.completeHeldSeek()
        advanceUntilIdle()
        assertTrue(events.ofType<PlaybackEvent.MediaEnded>().isEmpty())

        player.injectEnded() // the gate is closed again: EOF facts act
        advanceUntilIdle()
        assertEquals(MediaStatus.Ended, player.state.value.mediaStatus)
        assertEquals(1, events.ofType<PlaybackEvent.MediaEnded>().size)
        player.close()
    }
    // endregion

    // region notifyError: fails an in-flight open; acts in Ready/Ended; first error wins

    @Test
    fun `injectError at Idle is dropped`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.injectError(PlaybackException(PlaybackErrorCode.IO, "no session"))
        advanceUntilIdle()

        assertEquals(listOf(PlayerState.Initial), log.states)
        assertTrue(events.events.isEmpty())
        player.close()
    }

    @Test
    fun `injectError during a held open fails the open`(): TestResult = runTest {
        // Pins the async-error-during-Opening path (spec section 5 matrix: notifyError acts in
        // Opening by failing the open — setMediaData throws that error AND Error is emitted).
        // regression: C7 (errors carry a typed cause), C3-class (state cannot outrun the caller)
        val player = createPlayer()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)
        val data = TrackingMediaData()
        player.openBehavior = TestMediampPlayer.OpenBehavior.Hold()
        val error = PlaybackException(PlaybackErrorCode.IO, "device lost during prepare")

        var thrown: Throwable? = null
        val call = launch {
            try {
                player.setMediaData(data)
            } catch (e: PlaybackException) {
                thrown = e
            }
        }
        advanceUntilIdle()
        assertEquals(MediaStatus.Opening, player.state.value.mediaStatus)

        player.injectError(error) // async native error while openImpl is still suspended
        advanceUntilIdle()
        call.join()

        assertSame(error, thrown) // the caller observes the same instance...
        assertEquals(
            listOf(MediaStatus.Idle, MediaStatus.Opening, MediaStatus.Error(error)),
            log.statuses, // ...that is emitted as the Error status
        )
        assertEquals(1, data.closeCalls) // the in-flight resource is released exactly once
        assertNull(player.mediaData.value)
        assertEquals(listOf(error), events.ofType<PlaybackEvent.ErrorOccurred>().map { it.error })
        player.close()
    }

    @Test
    fun `injectError in Ready enters Error and releases the resource`(): TestResult = runTest {
        // regression: C1 (backend-driven ERROR released nothing in v1), C7
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data, playWhenReady = true)
        advanceUntilIdle()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)
        val error = PlaybackException(PlaybackErrorCode.DECODING, "decoder died")

        player.injectError(error)
        advanceUntilIdle()

        assertEquals(
            listOf(
                playerState(MediaStatus.Ready, playWhenReady = true),
                playerState(MediaStatus.Error(error)),
            ),
            log.states,
        )
        assertEquals(1, data.closeCalls)
        assertNull(player.mediaData.value)
        assertNull(player.mediaProperties.value)
        assertEquals(0L, player.currentPositionMillis.value)
        assertEquals(listOf(error), events.ofType<PlaybackEvent.ErrorOccurred>().map { it.error })
        player.close()
    }

    @Test
    fun `injectError at Ended enters Error and releases the retained media`(): TestResult = runTest {
        // regression: C1 (Ended retains the resource; a late fatal error must still release it)
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data, playWhenReady = true)
        player.injectEnded()
        advanceUntilIdle()
        assertFalse(data.closed)
        val log = recordStatesOf(player)
        val error = PlaybackException(PlaybackErrorCode.IO, "source vanished")

        player.injectError(error)
        advanceUntilIdle()

        assertEquals(
            listOf(playerState(MediaStatus.Ended), playerState(MediaStatus.Error(error))),
            log.states,
        )
        assertEquals(1, data.closeCalls)
        assertNull(player.mediaData.value)
        player.close()
    }

    @Test
    fun `second injectError at Error is dropped - first error wins`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        val first = PlaybackException(PlaybackErrorCode.IO, "first")
        player.injectError(first)
        advanceUntilIdle()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.injectError(PlaybackException(PlaybackErrorCode.INTERNAL, "second"))
        advanceUntilIdle()

        assertEquals(1, log.states.size) // no new emission
        val status = assertIs<MediaStatus.Error>(player.state.value.mediaStatus)
        assertSame(first, status.error)
        assertTrue(events.events.isEmpty())
        player.close()
    }

    @Test
    fun `injectError after close is dropped`(): TestResult = runTest {
        // regression: C3 (v1 could emit ERROR after DESTROYED)
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        advanceUntilIdle()
        player.close()
        advanceUntilIdle()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.injectError(PlaybackException(PlaybackErrorCode.IO, "late error"))
        advanceUntilIdle()

        assertEquals(listOf(playerState(MediaStatus.Released)), log.states)
        assertTrue(events.events.isEmpty())
    }
    // endregion

    // region notifyPosition / notifyProperties

    @Test
    fun `injectPosition during a held seek is dropped and applies after completion`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        advanceUntilIdle()
        player.holdSeeks = true

        player.seekTo(30_000L)
        assertEquals(30_000L, player.currentPositionMillis.value) // optimistic target

        player.injectPosition(50_000L) // stale progress from before the seek landed
        advanceUntilIdle()
        assertEquals(30_000L, player.currentPositionMillis.value) // held at the target

        player.completeHeldSeek()
        advanceUntilIdle()
        assertEquals(30_000L, player.currentPositionMillis.value)

        player.injectPosition(35_000L) // the gate is open: progress facts apply again
        advanceUntilIdle()
        assertEquals(35_000L, player.currentPositionMillis.value)
        player.close()
    }

    @Test
    fun `injectProperties acts during Opening`(): TestResult = runTest {
        val player = createPlayer()
        val hold = TestMediampPlayer.OpenBehavior.Hold()
        player.openBehavior = hold
        val call = async { player.setMediaData(TrackingMediaData()) }
        advanceUntilIdle()

        // A backend may learn metadata while still preparing (spec section 5 matrix:
        // notifyProperties acts in Opening).
        player.injectProperties(MediaProperties(title = "early metadata", durationMillis = 50_000L))
        advanceUntilIdle()
        assertEquals(MediaStatus.Opening, player.state.value.mediaStatus)
        assertEquals("early metadata", assertNotNull(player.mediaProperties.value).title)

        hold.release()
        advanceUntilIdle()
        call.await()
        // The Ready point delivers the open's own properties.
        assertEquals("Test Video", assertNotNull(player.mediaProperties.value).title)
        player.close()
    }

    @Test
    fun `injectProperties acts at Ended`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        player.injectEnded()
        advanceUntilIdle()

        val updated = assertNotNull(player.mediaProperties.value).copy(title = "Renamed")
        player.injectProperties(updated)
        advanceUntilIdle()

        assertEquals("Renamed", assertNotNull(player.mediaProperties.value).title)
        assertEquals(MediaStatus.Ended, player.state.value.mediaStatus)
        player.close()
    }

    @Test
    fun `injectProperties after stop is dropped`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        advanceUntilIdle()
        player.stopPlayback()

        player.injectProperties(MediaProperties(title = "stale", durationMillis = 1L))
        advanceUntilIdle()

        assertNull(player.mediaProperties.value) // invalidated session: fact dropped
        player.close()
    }
    // endregion

    // region stale session facts

    @Test
    fun `facts queued before an unload are dropped with their session`(): TestResult = runTest {
        // regression: C4 (v1: a late async FINISHED could land after the machine had already
        // moved on — here the queued fact dies with its invalidated session)
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        advanceUntilIdle()
        val events = recordEventsOf(player)

        player.injectEnded() // queued...
        player.stopPlayback() // ...but the session is invalidated before the fact drains
        advanceUntilIdle()

        assertEquals(PlayerState.Initial, player.state.value) // not Ended
        assertTrue(events.ofType<PlaybackEvent.MediaEnded>().isEmpty())
        player.close()
    }
    // endregion
}
