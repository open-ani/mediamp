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
import org.openani.mediamp.MediaLoadCancellationException
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.PlayerState
import org.openani.mediamp.togglePlayWhenReady
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Systematic coverage of EVERY cell of the spec section 3 command x status table:
 * `setMediaData`/`play`/`pause`/`stopPlayback`/`seekTo`/`close` at each of
 * Idle/Opening/Ready/Ended/Error/Released.
 *
 * "no-op" cells assert that nothing is emitted (`state.value` unchanged, no events, no native
 * commands); transition cells assert the exact spec'd emission sequences. I1/I2 are checked on
 * every emission by the recorders ([recordStatesOf]).
 *
 * This class (with its sibling area classes) is the conformance suite the v1 model never had.
 * // regression: C10 (no conformance suite / no emission-sequence assertions in v1)
 */
class CommandByStatusTableTest {

    private fun TestScope.createPlayer(): TestMediampPlayer =
        TestMediampPlayer(StandardTestDispatcher(testScheduler))

    // region Idle row

    @Test
    fun `play pause seekTo skip stopPlayback at Idle are no-ops`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.play()
        player.pause()
        player.seekTo(10_000L) // regression: C9, T2 (v1 seekTo was unguarded, acted in any state)
        player.skip(5_000L)
        player.stopPlayback()
        advanceUntilIdle()

        assertEquals(listOf(PlayerState.Initial), log.states)
        assertEquals(0L, player.currentPositionMillis.value)
        assertTrue(events.events.isEmpty())
        assertEquals(0, player.playCallCount) // no native commands issued either
        assertEquals(0, player.pauseCallCount)
        player.close()
    }

    @Test
    fun `setMediaData at Idle opens to Ready`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStatesOf(player)
        val data = TrackingMediaData()

        player.setMediaData(data)
        advanceUntilIdle()

        assertEquals(
            listOf(
                PlayerState.Initial,
                playerState(MediaStatus.Opening),
                playerState(MediaStatus.Ready),
            ),
            log.states,
        )
        assertSame(data, player.mediaData.value)
        assertFalse(data.closed)
        assertEquals(1, player.openCallCount)
        player.close()
    }

    @Test
    fun `close at Idle emits Released directly`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStatesOf(player)

        player.close()
        advanceUntilIdle()

        assertEquals(
            listOf(PlayerState.Initial, playerState(MediaStatus.Released)),
            log.states,
        )
    }
    // endregion

    // region Opening row (held opens)

    @Test
    fun `play during Opening applies to the in-flight session`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)
        val hold = TestMediampPlayer.OpenBehavior.Hold()
        player.openBehavior = hold

        val call = async { player.setMediaData(TrackingMediaData()) }
        advanceUntilIdle()
        assertEquals(playerState(MediaStatus.Opening), player.state.value)

        player.play() // pending intent := true, reflected immediately (spec section 3, note 3)
        assertEquals(playerState(MediaStatus.Opening, playWhenReady = true), player.state.value)

        hold.release()
        advanceUntilIdle()
        call.await()

        assertEquals(
            listOf(
                PlayerState.Initial,
                playerState(MediaStatus.Opening),
                playerState(MediaStatus.Opening, playWhenReady = true),
                playerState(MediaStatus.Ready, playWhenReady = true),
            ),
            log.states,
        )
        // The open handoff mismatch (openImpl captured the original intent) takes the
        // *applying* reconciliation path: one bounded re-command, no external-change event.
        assertTrue(player.nativePlayWhenReady)
        assertEquals(1, player.playCallCount)
        assertTrue(events.ofType<PlaybackEvent.ExternalPlayWhenReadyChanged>().isEmpty())
        player.close()
    }

    @Test
    fun `pause during Opening applies to the in-flight session`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)
        val hold = TestMediampPlayer.OpenBehavior.Hold()
        player.openBehavior = hold

        val call = async { player.setMediaData(TrackingMediaData(), playWhenReady = true) }
        advanceUntilIdle()
        assertEquals(playerState(MediaStatus.Opening, playWhenReady = true), player.state.value)

        player.pause() // a user's mid-open pause must not be lost (spec section 3, note 3)
        assertEquals(playerState(MediaStatus.Opening), player.state.value)

        hold.release()
        advanceUntilIdle()
        call.await()

        assertEquals(
            listOf(
                PlayerState.Initial,
                playerState(MediaStatus.Opening, playWhenReady = true),
                playerState(MediaStatus.Opening),
                playerState(MediaStatus.Ready),
            ),
            log.states,
        )
        assertFalse(player.nativePlayWhenReady)
        assertEquals(1, player.pauseCallCount)
        assertTrue(events.ofType<PlaybackEvent.ExternalPlayWhenReadyChanged>().isEmpty())
        player.close()
    }

    @Test
    fun `seekTo during Opening updates the start position optimistically`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStatesOf(player)
        val hold = TestMediampPlayer.OpenBehavior.Hold()
        player.openBehavior = hold

        val call = async { player.setMediaData(TrackingMediaData(), startPositionMillis = 5_000L) }
        advanceUntilIdle()
        assertEquals(5_000L, player.currentPositionMillis.value)

        player.seekTo(30_000L) // start position := clamp(p); no seek generation, no emission
        assertEquals(30_000L, player.currentPositionMillis.value)
        assertEquals(MediaStatus.Opening, player.state.value.mediaStatus)

        hold.release()
        advanceUntilIdle()
        call.await()

        assertEquals(30_000L, player.currentPositionMillis.value)
        assertEquals(
            listOf(MediaStatus.Idle, MediaStatus.Opening, MediaStatus.Ready),
            log.statuses, // the mid-open seek emitted no state change
        )
        player.close()
    }

    @Test
    fun `setMediaData during Opening supersedes without dipping to Idle`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStatesOf(player)
        val first = TrackingMediaData("test://first")
        val second = TrackingMediaData("test://second")

        player.openBehavior = TestMediampPlayer.OpenBehavior.Hold()
        val firstCall = async { player.setMediaData(first) }
        advanceUntilIdle()

        player.openBehavior = TestMediampPlayer.OpenBehavior.Immediate
        val secondCall = async { player.setMediaData(second) }
        advanceUntilIdle()

        assertFailsWith<MediaLoadCancellationException> { firstCall.await() }
        secondCall.await()

        assertEquals(1, first.closeCalls) // superseded resource released exactly once
        assertFalse(second.closed)
        assertSame(second, player.mediaData.value)
        assertEquals(2, player.openCallCount)
        assertEquals(
            listOf(MediaStatus.Idle, MediaStatus.Opening, MediaStatus.Ready),
            log.statuses, // Opening stayed Opening across the supersession
        )
        player.close()
    }

    @Test
    fun `stopPlayback during Opening cancels the open and returns to Idle`(): TestResult = runTest {
        // regression: C2 (v1 stopPlayback did not take the open mutex; racing opens leaked)
        val player = createPlayer()
        val log = recordStatesOf(player)
        val data = TrackingMediaData()
        player.openBehavior = TestMediampPlayer.OpenBehavior.Hold()

        val call = async { player.setMediaData(data) }
        advanceUntilIdle()

        player.stopPlayback()
        advanceUntilIdle()

        assertFailsWith<MediaLoadCancellationException> { call.await() }
        assertEquals(PlayerState.Initial, player.state.value)
        assertEquals(1, data.closeCalls)
        assertEquals(
            listOf(MediaStatus.Idle, MediaStatus.Opening, MediaStatus.Idle),
            log.statuses,
        )
        player.close()
    }

    @Test
    fun `close during Opening cancels the open and releases the data`(): TestResult = runTest {
        // regression: C2 (close racing an in-flight setMediaData)
        val player = createPlayer()
        val log = recordStatesOf(player)
        val data = TrackingMediaData()
        player.openBehavior = TestMediampPlayer.OpenBehavior.Hold()

        val call = async { player.setMediaData(data) }
        advanceUntilIdle()
        assertEquals(MediaStatus.Opening, player.state.value.mediaStatus)

        player.close()
        advanceUntilIdle()

        assertFailsWith<MediaLoadCancellationException> { call.await() }
        assertEquals(playerState(MediaStatus.Released), player.state.value)
        assertEquals(1, data.closeCalls) // teardown paths never leak (spec section 8)
        assertEquals(
            listOf(MediaStatus.Idle, MediaStatus.Opening, MediaStatus.Released),
            log.statuses, // no Idle or Error dip on the way out
        )
    }
    // endregion

    // region Ready row

    @Test
    fun `redundant play and pause at Ready emit nothing`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        advanceUntilIdle()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.play() // already playing: no emission, no native command
        advanceUntilIdle()
        assertEquals(listOf(playerState(MediaStatus.Ready, playWhenReady = true)), log.states)
        assertEquals(0, player.playCallCount) // the open handoff applied the intent, not playImpl

        player.pause()
        advanceUntilIdle()
        player.pause() // already paused: no second emission
        advanceUntilIdle()

        assertEquals(
            listOf(
                playerState(MediaStatus.Ready, playWhenReady = true),
                playerState(MediaStatus.Ready),
            ),
            log.states,
        )
        assertEquals(1, player.pauseCallCount)
        assertTrue(events.events.isEmpty())
        player.close()
    }

    @Test
    fun `rapid pause play during buffering both commit`(): TestResult = runTest {
        // regression: C5 (v1 guards read stale state; pause during buffering was dropped and
        // a rapid pause-resume lost the resume)
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        player.injectStall(true)
        advanceUntilIdle()
        val log = recordStatesOf(player)

        player.pause()
        player.play() // both apply synchronously, none dropped
        advanceUntilIdle()

        assertEquals(
            listOf(
                playerState(MediaStatus.Ready, playWhenReady = true, isBuffering = true),
                playerState(MediaStatus.Ready, playWhenReady = false, isBuffering = true),
                playerState(MediaStatus.Ready, playWhenReady = true, isBuffering = true),
            ),
            log.states,
        )
        assertTrue(player.nativePlayWhenReady)
        player.close()
    }

    @Test
    fun `togglePlayWhenReady is never dead including while buffering`(): TestResult = runTest {
        // regression: C8 (v1 togglePause was dead at READY and PAUSED_BUFFERING)
        val player = createPlayer()
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        player.injectStall(true)
        advanceUntilIdle()

        player.togglePlayWhenReady()
        assertFalse(player.state.value.playWhenReady)
        player.togglePlayWhenReady()
        assertTrue(player.state.value.playWhenReady)
        advanceUntilIdle()
        assertTrue(player.state.value.isBuffering) // orthogonal: toggling did not clear the stall
        player.close()
    }

    @Test
    fun `setMediaData with different data at Ready releases the old and reopens`(): TestResult = runTest {
        // regression: C1 (v1 leaked the previous openResource when the state guard skipped release)
        val player = createPlayer()
        val old = TrackingMediaData("test://old")
        player.setMediaData(old)
        advanceUntilIdle()
        val log = recordStatesOf(player)
        val new = TrackingMediaData("test://new")

        player.setMediaData(new)
        advanceUntilIdle()

        assertEquals(1, old.closeCalls) // released exactly once
        assertFalse(new.closed)
        assertSame(new, player.mediaData.value)
        assertEquals(2, player.openCallCount)
        assertEquals(
            listOf(MediaStatus.Ready, MediaStatus.Opening, MediaStatus.Ready),
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
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.setMediaData(data, playWhenReady = true, startPositionMillis = 30_000L)
        advanceUntilIdle()

        assertEquals(1, player.openCallCount) // same instance: no unload/reopen
        assertFalse(data.closed)
        assertEquals(30_000L, player.currentPositionMillis.value)
        assertEquals(30_000L, player.nativePositionMillis) // applied via a real native seek
        assertEquals(
            listOf(30_000L),
            events.ofType<PlaybackEvent.SeekCompleted>().map { it.positionMillis },
        )
        assertEquals(
            listOf(
                playerState(MediaStatus.Ready),
                playerState(MediaStatus.Ready, playWhenReady = true),
            ),
            log.states, // no Opening emitted
        )
        player.close()
    }

    @Test
    fun `equal data at Ready with identical params is a complete no-op`(): TestResult = runTest {
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data)
        advanceUntilIdle()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.setMediaData(data) // same instance, same intent (false), same position (0)
        advanceUntilIdle()

        assertEquals(listOf(playerState(MediaStatus.Ready)), log.states)
        assertTrue(events.events.isEmpty())
        assertEquals(1, player.openCallCount)
        assertFalse(data.closed)
        player.close()
    }

    @Test
    fun `seekTo at Ready is optimistic and completes without a status change`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData())
        advanceUntilIdle()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.seekTo(20_000L)
        assertEquals(20_000L, player.currentPositionMillis.value) // optimistic, same call
        advanceUntilIdle()

        assertEquals(20_000L, player.nativePositionMillis)
        assertEquals(
            listOf(20_000L),
            events.ofType<PlaybackEvent.SeekCompleted>().map { it.positionMillis },
        )
        assertEquals(listOf(playerState(MediaStatus.Ready)), log.states) // paused seek stays paused
        player.close()
    }

    @Test
    fun `stopPlayback at Ready unloads to Idle and releases the data`(): TestResult = runTest {
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data, playWhenReady = true)
        advanceUntilIdle()
        val log = recordStatesOf(player)

        player.stopPlayback()
        advanceUntilIdle()

        assertEquals(PlayerState.Initial, player.state.value)
        assertEquals(1, data.closeCalls)
        assertEquals(
            listOf(
                playerState(MediaStatus.Ready, playWhenReady = true),
                PlayerState.Initial,
            ),
            log.states,
        )
        player.close()
    }

    @Test
    fun `close at Ready releases the media and emits Released once`(): TestResult = runTest {
        val player = createPlayer()
        val data = TrackingMediaData()
        player.setMediaData(data)
        advanceUntilIdle()
        val log = recordStatesOf(player)

        player.close()
        advanceUntilIdle()

        assertEquals(
            listOf(playerState(MediaStatus.Ready), playerState(MediaStatus.Released)),
            log.states,
        )
        assertEquals(1, data.closeCalls)
    }
    // endregion

    // region Ended row

    private suspend fun TestScope.playerAtEnded(data: TrackingMediaData): TestMediampPlayer {
        val player = createPlayer()
        player.setMediaData(data, playWhenReady = true)
        player.injectEnded()
        advanceUntilIdle()
        assertEquals(MediaStatus.Ended, player.state.value.mediaStatus)
        return player
    }

    @Test
    fun `pause at Ended is a no-op`(): TestResult = runTest {
        val data = TrackingMediaData()
        val player = playerAtEnded(data)
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.pause()
        advanceUntilIdle()

        assertEquals(listOf(playerState(MediaStatus.Ended)), log.states)
        assertTrue(events.events.isEmpty())
        player.close()
    }

    @Test
    fun `play at Ended replays from the beginning without reopening`(): TestResult = runTest {
        val data = TrackingMediaData()
        val player = playerAtEnded(data)
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.play() // replay: seekTo(0) semantics + pwr := true in one atomic emission
        assertEquals(playerState(MediaStatus.Ready, playWhenReady = true), player.state.value)
        assertEquals(0L, player.currentPositionMillis.value)
        advanceUntilIdle()

        assertEquals(1, player.openCallCount) // no epoch bump, same media session
        assertSame(data, player.mediaData.value)
        assertFalse(data.closed)
        assertEquals(0L, player.nativePositionMillis)
        assertTrue(player.state.value.isPlaying)
        assertEquals(
            listOf(0L),
            events.ofType<PlaybackEvent.SeekCompleted>().map { it.positionMillis },
        )
        assertEquals(
            listOf(
                playerState(MediaStatus.Ended),
                playerState(MediaStatus.Ready, playWhenReady = true),
            ),
            log.states,
        )
        player.close()
    }

    @Test
    fun `seekTo at Ended re-enters Ready paused`(): TestResult = runTest {
        val data = TrackingMediaData()
        val player = playerAtEnded(data)
        val log = recordStatesOf(player)

        player.seekTo(50_000L)
        advanceUntilIdle()

        assertEquals(
            listOf(
                playerState(MediaStatus.Ended),
                playerState(MediaStatus.Ready), // paused (spec section 3, note 6)
            ),
            log.states,
        )
        assertEquals(50_000L, player.currentPositionMillis.value)
        assertEquals(50_000L, player.nativePositionMillis)
        assertEquals(1, player.openCallCount)
        player.close()
    }

    @Test
    fun `equal data at Ended replays with the requested params`(): TestResult = runTest {
        val data = TrackingMediaData()
        val player = playerAtEnded(data)
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        // Equal-data setMediaData at Ended: replay-seek to the start position, then intent.
        player.setMediaData(data, playWhenReady = true, startPositionMillis = 40_000L)
        advanceUntilIdle()

        assertEquals(1, player.openCallCount) // replay semantics: no reopen
        assertFalse(data.closed)
        assertEquals(40_000L, player.currentPositionMillis.value)
        assertEquals(40_000L, player.nativePositionMillis)
        assertTrue(player.state.value.isPlaying)
        assertEquals(
            listOf(40_000L),
            events.ofType<PlaybackEvent.SeekCompleted>().map { it.positionMillis },
        )
        assertEquals(
            listOf(
                playerState(MediaStatus.Ended),
                playerState(MediaStatus.Ready, playWhenReady = true),
            ),
            log.states,
        )
        player.close()
    }

    @Test
    fun `setMediaData with different data at Ended releases the old exactly once`(): TestResult = runTest {
        // regression: C1 (ended -> setMediaData must release the retained MediaData exactly once;
        // in v1 the FINISHED < READY guard skipped the release entirely)
        val old = TrackingMediaData("test://old")
        val player = playerAtEnded(old)
        assertFalse(old.closed) // Ended retains the resource
        val new = TrackingMediaData("test://new")

        player.setMediaData(new)
        advanceUntilIdle()

        assertEquals(1, old.closeCalls)
        assertFalse(new.closed)
        assertSame(new, player.mediaData.value)
        assertEquals(2, player.openCallCount)
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
        player.close()
    }

    @Test
    fun `stopPlayback at Ended releases the retained media`(): TestResult = runTest {
        val data = TrackingMediaData()
        val player = playerAtEnded(data)
        val log = recordStatesOf(player)

        player.stopPlayback()
        advanceUntilIdle()

        assertEquals(PlayerState.Initial, player.state.value)
        assertEquals(1, data.closeCalls)
        assertEquals(
            listOf(playerState(MediaStatus.Ended), PlayerState.Initial),
            log.states,
        )
        player.close()
    }

    @Test
    fun `close at Ended releases the retained media`(): TestResult = runTest {
        val data = TrackingMediaData()
        val player = playerAtEnded(data)
        val log = recordStatesOf(player)

        player.close()
        advanceUntilIdle()

        assertEquals(
            listOf(playerState(MediaStatus.Ended), playerState(MediaStatus.Released)),
            log.states,
        )
        assertEquals(1, data.closeCalls)
    }
    // endregion

    // region Error row

    private suspend fun TestScope.playerAtError(
        data: TrackingMediaData,
        error: PlaybackException = PlaybackException(PlaybackErrorCode.DECODING, "scripted error"),
    ): TestMediampPlayer {
        val player = createPlayer()
        player.setMediaData(data, playWhenReady = true)
        player.injectError(error)
        advanceUntilIdle()
        assertIs<MediaStatus.Error>(player.state.value.mediaStatus)
        return player
    }

    @Test
    fun `play pause seekTo at Error are no-ops`(): TestResult = runTest {
        val data = TrackingMediaData()
        val player = playerAtError(data)
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.play()
        player.pause()
        player.seekTo(10_000L) // regression: C9
        player.skip(-1_000L)
        advanceUntilIdle()

        assertEquals(1, log.states.size) // only the initial Error snapshot; nothing new emitted
        assertEquals(0L, player.currentPositionMillis.value)
        assertTrue(events.events.isEmpty())
        player.close()
    }

    @Test
    fun `setMediaData at Error opens a fresh media`(): TestResult = runTest {
        val data = TrackingMediaData()
        val player = playerAtError(data)
        val log = recordStatesOf(player)
        val fresh = TrackingMediaData("test://fresh")

        player.setMediaData(fresh)
        advanceUntilIdle()

        assertEquals(
            listOf(MediaStatus.Opening, MediaStatus.Ready),
            log.statuses.drop(1), // drop the initial Error snapshot
        )
        assertSame(fresh, player.mediaData.value)
        assertEquals(1, data.closeCalls) // the failed session's data was released on Error entry
        player.close()
    }

    @Test
    fun `stopPlayback at Error dismisses to Idle`(): TestResult = runTest {
        val data = TrackingMediaData()
        val player = playerAtError(data)
        val log = recordStatesOf(player)

        player.stopPlayback()
        advanceUntilIdle()

        assertEquals(PlayerState.Initial, player.state.value)
        assertEquals(1, data.closeCalls) // no double release: already released on Error entry
        assertEquals(
            listOf(MediaStatus.Error::class, MediaStatus.Idle::class),
            log.statuses.map { it::class },
        )
        player.close()
    }

    @Test
    fun `close at Error emits Released`(): TestResult = runTest {
        val data = TrackingMediaData()
        val player = playerAtError(data)
        val log = recordStatesOf(player)

        player.close()
        advanceUntilIdle()

        assertEquals(playerState(MediaStatus.Released), player.state.value)
        assertEquals(2, log.states.size) // Error snapshot + Released, nothing else
        assertEquals(1, data.closeCalls)
    }
    // endregion

    // region Released row

    @Test
    fun `all commands at Released are no-ops`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(TrackingMediaData())
        advanceUntilIdle()
        player.close()
        advanceUntilIdle()
        val log = recordStatesOf(player)
        val events = recordEventsOf(player)

        player.play()
        player.pause()
        player.seekTo(1_000L) // regression: C9 (v1 TestMediampPlayer seeked in DESTROYED)
        player.skip(1_000L)
        player.stopPlayback()
        player.close() // idempotent
        advanceUntilIdle()

        assertEquals(listOf(playerState(MediaStatus.Released)), log.states)
        assertEquals(0L, player.currentPositionMillis.value)
        assertTrue(events.events.isEmpty())
        assertEquals(0, player.playCallCount)
    }

    @Test
    fun `setMediaData at Released releases the data and returns normally`(): TestResult = runTest {
        val player = createPlayer()
        player.close()
        advanceUntilIdle()
        val log = recordStatesOf(player)
        val data = TrackingMediaData()

        player.setMediaData(data) // spec section 3 cell 2: never throws, never leaks
        advanceUntilIdle()

        assertEquals(1, data.closeCalls)
        assertEquals(listOf(playerState(MediaStatus.Released)), log.states)
        assertEquals(0, player.openCallCount)
    }
    // endregion
}
