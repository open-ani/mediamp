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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.features.PlaybackSpeed
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Real-thread coverage of the spec section 4 fail-fast rules: the machine captures the
 * identity of its main dispatcher's thread and rejects commands from any other thread.
 * (Common tests are single-threaded and cannot exercise these paths.)
 */
class ThreadFenceJvmTest {

    private fun <T> onBackgroundThread(block: () -> T): Result<T> {
        var result: Result<T>? = null
        thread(name = "mediamp-test-background") {
            result = runCatching(block)
        }.join()
        return result!!
    }

    @Test
    fun `commands from a background thread throw IllegalStateException`(): TestResult = runTest {
        val player = TestMediampPlayer(StandardTestDispatcher(testScheduler))
        player.setMediaData(TrackingMediaData()) // machine runs; thread identity captured
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)

        assertIs<IllegalStateException>(onBackgroundThread { player.play() }.exceptionOrNull())
        assertIs<IllegalStateException>(onBackgroundThread { player.pause() }.exceptionOrNull())
        assertIs<IllegalStateException>(onBackgroundThread { player.seekTo(1_000L) }.exceptionOrNull())
        assertIs<IllegalStateException>(onBackgroundThread { player.stopPlayback() }.exceptionOrNull())

        advanceUntilIdle()
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus) // nothing acted
        player.close()
    }

    @Test
    fun `PlaybackSpeed set from a background thread throws IllegalStateException`(): TestResult = runTest {
        // regression: v2 review — PlaybackSpeed.set had no thread enforcement, racing the
        // machine's desiredRate and calling setRateImpl off the main thread.
        val player = TestMediampPlayer(StandardTestDispatcher(testScheduler))
        player.setMediaData(TrackingMediaData(), playWhenReady = true)
        val speed = player.features[PlaybackSpeed]!!

        assertIs<IllegalStateException>(onBackgroundThread { speed.set(1.5f) }.exceptionOrNull())

        advanceUntilIdle()
        assertEquals(1f, speed.value) // the rejected call changed nothing
        player.close()
    }

    @Test
    fun `close from a background thread trampolines to the machine thread`(): TestResult = runTest {
        val player = TestMediampPlayer(StandardTestDispatcher(testScheduler))
        player.setMediaData(TrackingMediaData())
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)

        assertNull(onBackgroundThread { player.close() }.exceptionOrNull()) // any thread, no throw
        // Not committed synchronously from the background thread…
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)

        advanceUntilIdle()
        // …but on the machine's next turn.
        assertEquals(MediaStatus.Released, player.state.value.mediaStatus)
    }
}
