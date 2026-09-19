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
import org.openani.mediamp.features.NetworkStats
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.test.TestMediampPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NetworkStatsTest {
    private fun TestScope.createPlayer(): TestMediampPlayer =
        TestMediampPlayer(StandardTestDispatcher(testScheduler))

    @Test
    fun `feature is available`() = runTest {
        val player = createPlayer()
        assertNotNull(player.features[NetworkStats])
    }

    @Test
    fun `download speed is unknown before any media is opened`() = runTest {
        val player = createPlayer()
        val stats = player.features[NetworkStats]!!
        assertEquals(NetworkStats.UNKNOWN_SPEED, stats.downloadSpeedBytesPerSecond.first())
    }

    @Test
    fun `injected download speed is reported`() = runTest {
        val player = createPlayer()
        val stats = player.features[NetworkStats]!!
        player.setMediaData(UriMediaData("https://example.com/video.m3u8"))
        advanceUntilIdle()

        player.injectDownloadSpeed(1_500_000L)
        assertEquals(1_500_000L, stats.downloadSpeedBytesPerSecond.first())

        player.injectDownloadSpeed(0L)
        assertEquals(0L, stats.downloadSpeedBytesPerSecond.first())

        player.injectDownloadSpeed(NetworkStats.UNKNOWN_SPEED)
        assertEquals(NetworkStats.UNKNOWN_SPEED, stats.downloadSpeedBytesPerSecond.first())
    }

    @Test
    fun `download speed resets on open`() = runTest {
        val player = createPlayer()
        val stats = player.features[NetworkStats]!!
        player.setMediaData(UriMediaData("https://example.com/first.m3u8"))
        advanceUntilIdle()
        player.injectDownloadSpeed(1_000L)

        player.setMediaData(UriMediaData("https://example.com/second.m3u8"))
        advanceUntilIdle()
        assertEquals(NetworkStats.UNKNOWN_SPEED, stats.downloadSpeedBytesPerSecond.first())
    }

    @Test
    fun `download speed resets on stop`() = runTest {
        val player = createPlayer()
        val stats = player.features[NetworkStats]!!
        player.setMediaData(UriMediaData("https://example.com/video.m3u8"))
        advanceUntilIdle()
        player.injectDownloadSpeed(1_000L)

        player.stopPlayback()
        advanceUntilIdle()
        assertEquals(NetworkStats.UNKNOWN_SPEED, stats.downloadSpeedBytesPerSecond.first())
    }

    @Test
    fun `download speed resets on close`() = runTest {
        val player = createPlayer()
        val stats = player.features[NetworkStats]!!
        player.setMediaData(UriMediaData("https://example.com/video.m3u8"))
        advanceUntilIdle()
        player.injectDownloadSpeed(1_000L)

        player.close()
        advanceUntilIdle()
        assertEquals(NetworkStats.UNKNOWN_SPEED, stats.downloadSpeedBytesPerSecond.first())
    }
}
