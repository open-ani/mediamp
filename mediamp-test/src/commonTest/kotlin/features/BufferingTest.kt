/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(ExperimentalMediampApi::class)

package org.openani.mediamp.test.features

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.test.TestMediampPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BufferingTest {
    private fun TestScope.createPlayer(): TestMediampPlayer =
        TestMediampPlayer(StandardTestDispatcher(testScheduler))

    @Test
    fun `feature is available`() = runTest {
        val player = createPlayer()
        assertNotNull(player.features[Buffering])
    }

    @Test
    fun `buffered position is unknown before any media is opened`() = runTest {
        val player = createPlayer()
        val buffering = player.features[Buffering]!!
        assertEquals(Buffering.UNKNOWN_POSITION, buffering.bufferedPositionMillis.first())
        assertEquals(0, buffering.bufferedPercentage.first())
    }

    @Test
    fun `injected buffered position is reported`() = runTest {
        val player = createPlayer()
        val buffering = player.features[Buffering]!!
        player.setMediaData(UriMediaData("file:///fake.mp4"))
        advanceUntilIdle()

        player.injectBufferedPosition(42_000L)
        assertEquals(42_000L, buffering.bufferedPositionMillis.first())

        player.injectBufferedPosition(Buffering.UNKNOWN_POSITION)
        assertEquals(Buffering.UNKNOWN_POSITION, buffering.bufferedPositionMillis.first())
    }

    @Test
    fun `buffered position resets on open`() = runTest {
        val player = createPlayer()
        val buffering = player.features[Buffering]!!
        player.setMediaData(UriMediaData("file:///first.mp4"))
        advanceUntilIdle()
        player.injectBufferedPosition(42_000L)

        player.setMediaData(UriMediaData("file:///second.mp4"))
        advanceUntilIdle()
        assertEquals(Buffering.UNKNOWN_POSITION, buffering.bufferedPositionMillis.first())
    }

    @Test
    fun `buffered position resets on stop`() = runTest {
        val player = createPlayer()
        val buffering = player.features[Buffering]!!
        player.setMediaData(UriMediaData("file:///fake.mp4"))
        advanceUntilIdle()
        player.injectBufferedPosition(42_000L)

        player.stopPlayback()
        advanceUntilIdle()
        assertEquals(Buffering.UNKNOWN_POSITION, buffering.bufferedPositionMillis.first())
    }

    @Test
    fun `buffered position resets on close`() = runTest {
        val player = createPlayer()
        val buffering = player.features[Buffering]!!
        player.setMediaData(UriMediaData("file:///fake.mp4"))
        advanceUntilIdle()
        player.injectBufferedPosition(42_000L)

        player.close()
        advanceUntilIdle()
        assertEquals(Buffering.UNKNOWN_POSITION, buffering.bufferedPositionMillis.first())
    }
}
