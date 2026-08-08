/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(
    ExperimentalCoroutinesApi::class,
    ExperimentalMediampApi::class,
    InternalMediampApi::class,
    InternalForInheritanceMediampApi::class,
)

package org.openani.mediamp.test

import kotlinx.coroutines.CoroutineDispatcher
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
import org.openani.mediamp.AbstractMediampPlayer
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.MediaLoadCancellationException
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.OpenResult
import org.openani.mediamp.PlaybackSessionHandle
import org.openani.mediamp.PlayerState
import org.openani.mediamp.TransportSnapshot
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.buildPlayerFeatures
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.source.MediaData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Coverage of the spec section 4 threading rules and the session/open lifecycle edges:
 * close() from any thread, close idempotency, parent-Job-bounded lifetime, caller
 * cancellation, and supersession of an in-flight open.
 */
class LifecycleTest {

    private fun TestScope.createPlayer(): TestMediampPlayer =
        TestMediampPlayer(StandardTestDispatcher(testScheduler))

    /**
     * A minimal backend for exercising close() scheduling — the machine thread is captured on
     * the machine's first execution, so behavior before that capture is observable here.
     * Real off-thread checks need real threads and live in the JVM-only ThreadFenceJvmTest.
     */
    private class ThreadAwarePlayer(
        dispatcher: CoroutineDispatcher,
    ) : AbstractMediampPlayer(
        parentCoroutineContext = dispatcher,
        mainDispatcher = dispatcher,
        releaseDispatcher = dispatcher,
    ) {
        var closeImplCalls: Int = 0
            private set

        override val impl: Any get() = this
        override val features: PlayerFeatures = buildPlayerFeatures {}

        override suspend fun openImpl(
            data: MediaData,
            session: PlaybackSessionHandle,
            playWhenReady: Boolean,
            startPositionMillis: Long,
        ): OpenResult = OpenResult(
            initialSnapshot = TransportSnapshot(nativePlayWhenReady = playWhenReady, isStalled = false),
            initialProperties = MediaProperties(title = "thread test", durationMillis = 100_000L),
        )

        override fun playImpl() {}
        override fun pauseImpl() {}
        override fun seekImpl(positionMillis: Long, seekGeneration: Int) {}
        override fun setRateImpl(rate: Float) {}
        override fun stopImpl() {}
        override fun closeImpl() {
            closeImplCalls++
        }
    }

    @Test
    fun `close before the machine thread is captured defers to the machine turn`(): TestResult = runTest {
        // regression: C6 family (v1 ran closeImpl on arbitrary threads; v2 close() is
        // any-thread and trampolines whenever it cannot prove it is on the machine thread —
        // including before the machine ever ran and captured its thread identity)
        val player = ThreadAwarePlayer(StandardTestDispatcher(testScheduler))

        player.close()
        // Machine thread not captured yet: Released is NOT committed synchronously…
        assertEquals(MediaStatus.Idle, player.state.value.mediaStatus)
        assertEquals(0, player.closeImplCalls)

        advanceUntilIdle()
        // …but on the next main-dispatcher turn, exactly once.
        assertEquals(playerState(MediaStatus.Released), player.state.value)
        assertEquals(1, player.closeImplCalls)
    }

    @Test
    fun `close is idempotent and emits Released exactly once`(): TestResult = runTest {
        val player = ThreadAwarePlayer(StandardTestDispatcher(testScheduler))
        player.setMediaData(TrackingMediaData())
        val log = recordStatesOf(player)

        player.close()
        player.close()
        advanceUntilIdle()
        player.close()
        advanceUntilIdle()

        assertEquals(1, player.closeImplCalls)
        assertEquals(
            listOf(playerState(MediaStatus.Ready), playerState(MediaStatus.Released)),
            log.states, // exactly one Released emission
        )
    }

    @Test
    fun `player closes and releases the media when the parent job completes`(): TestResult = runTest {
        val parentJob = Job()
        val player = TestMediampPlayer(StandardTestDispatcher(testScheduler) + parentJob)
        val data = TrackingMediaData()
        player.setMediaData(data)
        advanceUntilIdle()
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)

        parentJob.cancel()
        advanceUntilIdle()

        assertEquals(playerState(MediaStatus.Released), player.state.value)
        assertEquals(1, data.closeCalls) // lifetime-bound teardown releases the resource

        // Terminal for real: commands and injections are dead.
        player.play()
        player.injectEnded()
        advanceUntilIdle()
        assertEquals(playerState(MediaStatus.Released), player.state.value)
    }

    @Test
    fun `caller cancellation during a held open returns to Idle and releases the data`(): TestResult = runTest {
        val player = createPlayer()
        val log = recordStatesOf(player)
        val data = TrackingMediaData()
        player.openBehavior = TestMediampPlayer.OpenBehavior.Hold()

        val caller = launch { player.setMediaData(data) }
        advanceUntilIdle()
        assertEquals(MediaStatus.Opening, player.state.value.mediaStatus)

        caller.cancel() // the CALLER gives up (e.g. its screen scope is destroyed)
        advanceUntilIdle()

        assertTrue(caller.isCancelled)
        assertEquals(PlayerState.Initial, player.state.value) // this call owned the active Opening
        assertEquals(1, data.closeCalls) // ownership-at-entry: released exactly once
        assertEquals(
            listOf(MediaStatus.Idle, MediaStatus.Opening, MediaStatus.Idle),
            log.statuses,
        )
        player.close()
    }

    @Test
    fun `caller cancellation after Ready committed keeps the session intact`(): TestResult = runTest {
        // Spec section 3: if Ready had already committed, the resource is live and NOT released;
        // only the caller's CancellationException propagates.
        val player = createPlayer()
        val data = TrackingMediaData()
        lateinit var caller: Job
        // Cancel the caller from inside the Ready commit — after installation, before the
        // suspended setMediaData call resumes.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.state.collect {
                if (it.mediaStatus == MediaStatus.Ready) caller.cancel()
            }
        }

        caller = launch { player.setMediaData(data) }
        advanceUntilIdle()

        assertTrue(caller.isCancelled)
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus) // session survives
        assertSame(data, player.mediaData.value)
        assertFalse(data.closed) // the live resource is NOT released
        player.close()
        advanceUntilIdle()
        assertEquals(1, data.closeCalls) // …until a real teardown path
    }

    @Test
    fun `supersession cancels the first caller with MediaLoadCancellationException`(): TestResult = runTest {
        val player = createPlayer()
        val first = TrackingMediaData("test://first")
        val second = TrackingMediaData("test://second")
        player.openBehavior = TestMediampPlayer.OpenBehavior.Hold()

        val firstCall = async { player.setMediaData(first) }
        advanceUntilIdle()

        player.openBehavior = TestMediampPlayer.OpenBehavior.Immediate
        val secondCall = async { player.setMediaData(second) }
        advanceUntilIdle()

        // MediaLoadCancellationException is a CancellationException subclass: structured
        // concurrency treats supersession as cancellation by default; recovery handlers must
        // ensureActive() before acting (spec section 3).
        assertFailsWith<MediaLoadCancellationException> { firstCall.await() }
        assertEquals(1, first.closeCalls) // the superseded data is released
        secondCall.await()
        assertSame(second, player.mediaData.value) // the second call installed
        assertFalse(second.closed)
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
        player.close()
    }

    @Test
    fun `stopPlayback aborting an open releases exactly once even with a queued completion`(): TestResult = runTest {
        // The open completes natively at the same moment the user stops: only one of the two
        // paths may release the resource.
        val player = createPlayer()
        val data = TrackingMediaData()
        val hold = TestMediampPlayer.OpenBehavior.Hold()
        player.openBehavior = hold

        val caller = async { player.setMediaData(data) }
        advanceUntilIdle()

        hold.release() // native completion queued…
        player.stopPlayback() // …but the stop wins the race on the machine thread
        advanceUntilIdle()

        assertFailsWith<MediaLoadCancellationException> { caller.await() }
        assertEquals(PlayerState.Initial, player.state.value)
        assertEquals(1, data.closeCalls)
        assertNull(player.mediaData.value)
        player.close()
    }
}
