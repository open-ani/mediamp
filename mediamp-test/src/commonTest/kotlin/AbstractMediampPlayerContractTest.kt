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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.MediaLoadCancellationException
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.PlayerState
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.SeekableInputMediaData
import kotlin.coroutines.CoroutineContext
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
 * The seed of the conformance suite (spec `docs/playback-state-v2.md` section 10): drives a
 * [MediampPlayer] through the section 3 command x status table and the section 5 notification
 * matrix, asserting **full emission sequences** and the I1/I2 invariants on every observed
 * emission.
 *
 * Runs against [TestMediampPlayer], whose injection surface is normative for the fake-backend
 * side of the contract.
 */
class AbstractMediampPlayerContractTest {

    // region harness

    private fun TestScope.createPlayer(): TestMediampPlayer =
        TestMediampPlayer(StandardTestDispatcher(testScheduler))

    /** Records every state emission, checking invariants I1/I2 on each. */
    private class StateLog(val states: MutableList<PlayerState>) {
        val statuses: List<MediaStatus> get() = states.map { it.mediaStatus }
    }

    /**
     * Collects with an unconfined collector so every single emission is observed inline
     * (full sequences, no conflation) — the pattern recommended by kotlinx-coroutines-test.
     */
    private fun TestScope.recordStates(player: MediampPlayer): StateLog {
        val states = mutableListOf<PlayerState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.state.collect {
                // Invariants (spec section 1), checked on every emission:
                if (it.isBuffering) {
                    assertEquals(MediaStatus.Ready, it.mediaStatus, "I1 violated: $it")
                }
                if (it.playWhenReady) {
                    assertTrue(
                        it.mediaStatus == MediaStatus.Opening || it.mediaStatus == MediaStatus.Ready,
                        "I2 violated: $it",
                    )
                }
                states += it
            }
        }
        return StateLog(states)
    }

    private class EventLog(val events: MutableList<PlaybackEvent>) {
        inline fun <reified T : PlaybackEvent> ofType(): List<T> = events.filterIsInstance<T>()
    }

    private fun TestScope.recordEvents(player: MediampPlayer): EventLog {
        val events = mutableListOf<PlaybackEvent>()
        // Unconfined: subscribes before this returns (events have no replay) and observes inline.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.events.collect { events += it }
        }
        return EventLog(events)
    }

    /** A [SeekableInputMediaData] that records [close] calls, for resource-lifecycle assertions. */
    private class TrackingMediaData(
        override val uri: String = "test://tracking",
    ) : SeekableInputMediaData {
        var closeCalls: Int = 0
            private set
        val closed: Boolean get() = closeCalls > 0

        override val extraFiles: MediaExtraFiles get() = MediaExtraFiles.EMPTY
        override val options: List<String> get() = emptyList()
        override fun fileLength(): Long? = null
        override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput =
            throw UnsupportedOperationException("not used by TestMediampPlayer")

        override fun close() {
            closeCalls++
        }
    }

    private fun state(status: MediaStatus, playWhenReady: Boolean = false, isBuffering: Boolean = false) =
        PlayerState(status, playWhenReady, isBuffering)
    // endregion

    // region command x status: Idle

    @Test
    fun `commands at Idle are no-ops`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStates(player)
        val events = recordEvents(player)

        player.play()
        player.pause()
        player.seekTo(10_000L) // no-op, not applied (v1 defect C9/T2: seekTo was unguarded)
        player.skip(5_000L)
        player.stopPlayback()
        advanceUntilIdle()

        assertEquals(listOf(PlayerState.Initial), log.states) // nothing emitted
        assertEquals(0L, player.currentPositionMillis.value)
        assertTrue(events.events.isEmpty())
        player.close()
    }
    // endregion

    // region setMediaData

    @Test
    fun `setMediaData emits Opening then Ready in order`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStates(player)
        val data = TrackingMediaData()

        player.setMediaData(data)
        advanceUntilIdle()

        assertEquals(
            listOf(
                PlayerState.Initial,
                state(MediaStatus.Opening),
                state(MediaStatus.Ready),
            ),
            log.states,
        )
        assertSame(data, player.mediaData.value)
        assertFalse(data.closed)
        player.close()
    }

    @Test
    fun `setMediaData with playWhenReady carries the intent through Opening`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStates(player)

        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        advanceUntilIdle()

        assertEquals(
            listOf(
                PlayerState.Initial,
                state(MediaStatus.Opening, playWhenReady = true),
                state(MediaStatus.Ready, playWhenReady = true),
            ),
            log.states,
        )
        assertTrue(player.state.value.isPlaying)
        player.close()
    }

    @Test
    fun `side flows are set before the Ready emission`(): TestResult = runTest {
        val player = createPlayer()
        val data = TrackingMediaData()
        // Spec section 9: an observer waking on a mediaStatus change never reads stale side flows.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.state.collect {
                if (it.mediaStatus == MediaStatus.Ready) {
                    assertSame(data, player.mediaData.value, "mediaData must be set before Ready is emitted")
                    assertNotNull(player.mediaProperties.value, "mediaProperties must be set before Ready is emitted")
                }
            }
        }

        player.setMediaData(data)
        advanceUntilIdle()
        player.close()
    }

    @Test
    fun `superseding setMediaData cancels the previous open and releases its resource`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStates(player)
        val first = TrackingMediaData("test://first")
        val second = TrackingMediaData("test://second")

        val holdFirst = TestMediampPlayer.OpenBehavior.Hold()
        player.openBehavior = holdFirst
        val firstCall = async { player.setMediaData(first) }
        advanceUntilIdle()
        assertEquals(MediaStatus.Opening, player.state.value.mediaStatus)

        val holdSecond = TestMediampPlayer.OpenBehavior.Hold()
        player.openBehavior = holdSecond
        val secondCall = async { player.setMediaData(second) }
        advanceUntilIdle()

        assertTrue(firstCall.isCancelled)
        assertFailsWith<MediaLoadCancellationException> { firstCall.await() }
        assertTrue(first.closed) // the superseded resource is released exactly once
        assertEquals(1, first.closeCalls)
        assertEquals(MediaStatus.Opening, player.state.value.mediaStatus) // still opening the second

        holdSecond.release()
        advanceUntilIdle()
        secondCall.await()
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
        assertSame(second, player.mediaData.value)
        assertFalse(second.closed)
        // The supersession never dipped back to Idle: Opening stayed Opening.
        assertEquals(
            listOf(MediaStatus.Idle, MediaStatus.Opening, MediaStatus.Ready),
            log.statuses,
        )
        player.close()
    }

    @Test
    fun `equal data at Ready applies params without reopening`(): TestResult = runTest {
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data)
        advanceUntilIdle()
        val log = recordStates(player)

        player.setMediaData(data, playWhenReady = true, startPositionMillis = 30_000L)
        advanceUntilIdle()

        assertEquals(1, player.openCallCount) // no unload/reopen for the same instance
        assertFalse(data.closed)
        assertEquals(30_000L, player.currentPositionMillis.value)
        assertTrue(player.state.value.playWhenReady)
        assertFalse(log.statuses.contains(MediaStatus.Opening)) // no Opening emitted
        player.close()
    }

    @Test
    fun `setMediaData from Error recovers`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        player.injectError(PlaybackException(PlaybackErrorCode.IO, "boom"))
        advanceUntilIdle()
        assertIs<MediaStatus.Error>(player.state.value.mediaStatus)
        val log = recordStates(player)

        val fresh = TrackingMediaData("test://fresh")
        player.setMediaData(fresh)
        advanceUntilIdle()

        assertEquals(
            listOf(MediaStatus.Opening, MediaStatus.Ready),
            log.statuses.drop(1), // drop the initial Error snapshot the recorder collected
        )
        assertSame(fresh, player.mediaData.value)
        player.close()
    }
    // endregion

    // region pause during stall (buffering-while-paused, v1 defects E4/A2)

    @Test
    fun `pause during stall applies immediately and buffering is retained`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        advanceUntilIdle()
        val log = recordStates(player)

        player.injectStall(true)
        advanceUntilIdle()
        assertEquals(state(MediaStatus.Ready, playWhenReady = true, isBuffering = true), player.state.value)

        player.pause() // v1 dropped this until buffering completed; v2 applies it synchronously
        assertFalse(player.state.value.playWhenReady)
        assertTrue(player.state.value.isBuffering) // orthogonal axes: pausing does not clear the stall
        advanceUntilIdle()

        player.injectStall(false)
        advanceUntilIdle()

        assertEquals(
            listOf(
                state(MediaStatus.Ready, playWhenReady = true, isBuffering = false),
                state(MediaStatus.Ready, playWhenReady = true, isBuffering = true),
                state(MediaStatus.Ready, playWhenReady = false, isBuffering = true),
                state(MediaStatus.Ready, playWhenReady = false, isBuffering = false),
            ),
            log.states,
        )
        player.close()
    }
    // endregion

    // region stopPlayback

    @Test
    fun `stopPlayback from Ready returns to Idle and resets side flows`(): TestResult = runTest {
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data, playWhenReady = true, startPositionMillis = 10_000L)
        advanceUntilIdle()
        val log = recordStates(player)

        player.stopPlayback()
        advanceUntilIdle()

        assertEquals(PlayerState.Initial, player.state.value)
        assertNull(player.mediaData.value)
        assertNull(player.mediaProperties.value)
        assertEquals(0L, player.currentPositionMillis.value)
        assertTrue(data.closed) // spec section 8: stopPlayback releases the MediaData
        assertEquals(1, data.closeCalls)
        assertEquals(
            listOf(
                state(MediaStatus.Ready, playWhenReady = true),
                PlayerState.Initial,
            ),
            log.states,
        )
        player.close()
    }

    @Test
    fun `stopPlayback during Opening aborts the open`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStates(player)
        val data = TrackingMediaData()
        val hold = TestMediampPlayer.OpenBehavior.Hold()
        player.openBehavior = hold

        val call = async { player.setMediaData(data) }
        advanceUntilIdle()
        assertEquals(MediaStatus.Opening, player.state.value.mediaStatus)

        player.stopPlayback()
        advanceUntilIdle()

        assertTrue(call.isCancelled)
        assertFailsWith<MediaLoadCancellationException> { call.await() }
        assertEquals(PlayerState.Initial, player.state.value)
        assertTrue(data.closed) // the in-flight resource is released
        assertEquals(
            listOf(MediaStatus.Idle, MediaStatus.Opening, MediaStatus.Idle),
            log.statuses,
        )
        player.close()
    }

    @Test
    fun `stopPlayback from Ended releases the retained media`(): TestResult = runTest {
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data, playWhenReady = true)
        player.injectEnded()
        advanceUntilIdle()
        assertEquals(MediaStatus.Ended, player.state.value.mediaStatus)
        assertFalse(data.closed) // Ended retains the resource (replay is cheap)

        player.stopPlayback()
        advanceUntilIdle()

        assertEquals(PlayerState.Initial, player.state.value)
        assertTrue(data.closed)
        player.close()
    }
    // endregion

    // region close (Released terminal)

    @Test
    fun `close is terminal and further commands are no-ops`(): TestResult = runTest {
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data)
        advanceUntilIdle()
        val log = recordStates(player)

        player.close()
        advanceUntilIdle()

        assertEquals(state(MediaStatus.Released), player.state.value)
        assertNull(player.mediaData.value)
        assertTrue(data.closed)
        val emissionsAtClose = log.states.toList()

        // I3: no commands act, no flows emit, ever.
        player.play()
        player.pause()
        player.seekTo(1_000L)
        player.stopPlayback()
        player.close() // idempotent
        advanceUntilIdle()

        assertEquals(emissionsAtClose, log.states)
        assertEquals(state(MediaStatus.Released), player.state.value)
        assertEquals(0L, player.currentPositionMillis.value)
    }

    @Test
    fun `setMediaData at Released releases the data and returns normally`(): TestResult = runTest {
        val player = createPlayer()
        player.close()
        advanceUntilIdle()
        val data = TrackingMediaData()

        player.setMediaData(data) // spec section 3 cell 2: teardown paths never throw, never leak
        advanceUntilIdle()

        assertTrue(data.closed)
        assertEquals(state(MediaStatus.Released), player.state.value)
    }
    // endregion

    // region error entry

    @Test
    fun `error entry releases resources and pins intent false`(): TestResult = runTest {
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data, playWhenReady = true)
        advanceUntilIdle()
        val log = recordStates(player)
        val events = recordEvents(player)
        val error = PlaybackException(PlaybackErrorCode.DECODING, "mid-playback failure")

        player.injectError(error)
        advanceUntilIdle()

        assertEquals(
            listOf(
                state(MediaStatus.Ready, playWhenReady = true),
                state(MediaStatus.Error(error)), // playWhenReady=false in the same atomic emission (I2)
            ),
            log.states,
        )
        assertNull(player.mediaData.value) // v1 defect C1: backend-driven ERROR leaked the resource
        assertTrue(data.closed)
        assertEquals(1, data.closeCalls)
        assertNull(player.mediaProperties.value)
        assertEquals(0L, player.currentPositionMillis.value)
        assertEquals(listOf(error), events.ofType<PlaybackEvent.ErrorOccurred>().map { it.error })
        player.close()
    }

    @Test
    fun `open failure emits the thrown exception as Error`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStates(player)
        val events = recordEvents(player)
        val error = PlaybackException(PlaybackErrorCode.IO, "unreachable source")
        player.openBehavior = TestMediampPlayer.OpenBehavior.Fail(error)
        val data = TrackingMediaData()

        val thrown = assertFailsWith<PlaybackException> { player.setMediaData(data) }
        advanceUntilIdle()

        assertSame(error, thrown) // same instance thrown and emitted (spec section 7)
        assertEquals(
            listOf(MediaStatus.Idle, MediaStatus.Opening, MediaStatus.Error(error)),
            log.statuses,
        )
        assertTrue(data.closed) // failed open releases the resource
        assertEquals(listOf(error), events.ofType<PlaybackEvent.ErrorOccurred>().map { it.error })
        player.close()
    }
    // endregion

    // region ended, replay

    @Test
    fun `ended retains mediaData and pins intent false`(): TestResult = runTest {
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data, playWhenReady = true)
        advanceUntilIdle()
        val log = recordStates(player)
        val events = recordEvents(player)

        player.injectEnded()
        advanceUntilIdle()

        assertEquals(
            listOf(
                state(MediaStatus.Ready, playWhenReady = true),
                state(MediaStatus.Ended), // playWhenReady=false in the same atomic emission (I2)
            ),
            log.states,
        )
        assertSame(data, player.mediaData.value) // retained: replay is cheap (v1 defect C1 unrepresentable)
        assertFalse(data.closed)
        assertNotNull(player.mediaProperties.value)
        assertFalse(player.nativePlayWhenReady) // spec section 6: machine issued pauseImpl on Ended entry

        val ended = assertNotNull(events.ofType<PlaybackEvent.MediaEnded>().singleOrNull())
        assertSame(data, ended.mediaData)
        assertEquals(100_000L, ended.durationMillis)
        player.close()
    }

    @Test
    fun `play from Ended replays from the beginning without reopening`(): TestResult = runTest {
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data, playWhenReady = true)
        player.injectEnded()
        advanceUntilIdle()
        val log = recordStates(player)
        val events = recordEvents(player)

        player.play() // replay (spec section 3): seek(0) + playWhenReady=true, same session
        assertEquals(state(MediaStatus.Ready, playWhenReady = true), player.state.value)
        assertEquals(0L, player.currentPositionMillis.value)
        advanceUntilIdle()

        assertEquals(1, player.openCallCount) // no reopen: same media session
        assertSame(data, player.mediaData.value)
        assertFalse(data.closed)
        assertTrue(player.state.value.isPlaying)
        assertEquals(0L, player.nativePositionMillis) // the replay seek was applied natively
        assertEquals(
            listOf(0L),
            events.ofType<PlaybackEvent.SeekCompleted>().map { it.positionMillis },
        )
        assertEquals(
            listOf(
                state(MediaStatus.Ended),
                state(MediaStatus.Ready, playWhenReady = true),
            ),
            log.states,
        )
        player.close()
    }

    @Test
    fun `seek from Ended re-enters Ready paused`(): TestResult = runTest {
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data, playWhenReady = true)
        player.injectEnded()
        advanceUntilIdle()

        player.seekTo(50_000L)
        assertEquals(state(MediaStatus.Ready, playWhenReady = false), player.state.value)
        assertEquals(50_000L, player.currentPositionMillis.value)
        advanceUntilIdle()

        assertEquals(1, player.openCallCount) // same session
        assertEquals(50_000L, player.nativePositionMillis)
        assertEquals(state(MediaStatus.Ready, playWhenReady = false), player.state.value) // stays paused
        player.close()
    }
    // endregion

    // region seek gating (spec section 5)

    @Test
    fun `seek gating drops stale ended and position facts but passes stalls`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        advanceUntilIdle()
        val events = recordEvents(player)
        player.holdSeeks = true

        player.seekTo(30_000L)
        assertEquals(30_000L, player.currentPositionMillis.value) // optimistic emission

        // Queued stale facts from before the seek land inside the window and die (spec section 5):
        player.injectEnded()
        player.injectPosition(99_999L)
        advanceUntilIdle()
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus) // stale ended dropped
        assertEquals(30_000L, player.currentPositionMillis.value) // stale position dropped

        // Stall facts flow freely: the spinner covers post-seek stalls (v1 defect R2/M9):
        player.injectStall(true)
        advanceUntilIdle()
        assertTrue(player.state.value.isBuffering)
        player.injectStall(false)
        advanceUntilIdle()

        player.completeHeldSeek()
        advanceUntilIdle()
        assertEquals(
            listOf(30_000L),
            events.ofType<PlaybackEvent.SeekCompleted>().map { it.positionMillis },
        )

        // The gate is closed: end-of-media facts act again.
        player.injectEnded()
        advanceUntilIdle()
        assertEquals(MediaStatus.Ended, player.state.value.mediaStatus)
        player.close()
    }
    // endregion

    // region lifetime binding

    @Test
    fun `player closes when the parent job completes`(): TestResult = runTest {
        val parentJob = Job()
        val player = TestMediampPlayer(StandardTestDispatcher(testScheduler) + parentJob)
        player.setMediaData(TrackingMediaData())
        advanceUntilIdle()
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)

        parentJob.cancel()
        advanceUntilIdle()

        assertEquals(state(MediaStatus.Released), player.state.value)
    }
    // endregion
}
