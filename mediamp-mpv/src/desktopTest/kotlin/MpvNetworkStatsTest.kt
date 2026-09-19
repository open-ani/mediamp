/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(ExperimentalMediampApi::class, InternalMediampApi::class)

package org.openani.mediamp.mpv

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.NetworkStats
import org.openani.mediamp.mpv.utils.MpvTestMedia
import org.openani.mediamp.mpv.utils.RangeFileServer
import org.openani.mediamp.playUri
import org.openani.mediamp.source.UriMediaData
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [NetworkStats.downloadSpeedBytesPerSecond] against a real libmpv, driven by mpv's
 * `cache-speed` property:
 *
 * - unknown before any media is open, for a local file (no stream cache), and again after stop;
 * - for a bandwidth-limited HTTP source the reported speed matches the server's limit;
 * - once the whole clip is cached the speed drops to `0` (idle), not to unknown.
 */
class MpvNetworkStatsTest {

    private fun prepareOrSkip(): Boolean = MpvTestMedia.prepareOrSkip(TAG)

    private fun skip(reason: String): Boolean = MpvTestMedia.skip(TAG, reason)

    private fun generateClip(): File? = MpvTestMedia.generateClip(CLIP_SECONDS)

    private fun withPlayer(block: suspend (player: MpvMediampPlayer, stats: NetworkStats) -> Unit) {
        // Single physical thread as the player's main dispatcher; see MpvBufferingTest.
        val mainDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mpv-network-stats-test-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        try {
            runBlocking(mainDispatcher) {
                val player = MpvMediampPlayer(Any(), coroutineContext, mainDispatcher = mainDispatcher)
                try {
                    (player.impl as MPVHandle).setPropertyString("ao", "null")
                    val stats = checkNotNull(player.features[NetworkStats]) { "mpv player must expose NetworkStats" }
                    block(player, stats)
                } finally {
                    player.close()
                }
            }
        } finally {
            mainDispatcher.close()
        }
    }

    @Test
    fun `unknown before any media is open`() {
        if (!prepareOrSkip()) return
        withPlayer { _, stats ->
            assertEquals(NetworkStats.UNKNOWN_SPEED, stats.downloadSpeedBytesPerSecond.first())
        }
    }

    @Test
    fun `local file - stays unknown`() {
        if (!prepareOrSkip()) return
        val clip = generateClip() ?: run { skip("ffmpeg unavailable or clip generation failed"); return }
        withPlayer { player, stats ->
            player.playUri(clip.absolutePath)
            assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
            withTimeout(15_000) { player.currentPositionMillis.first { it > 2_000 } }
            assertEquals(
                NetworkStats.UNKNOWN_SPEED, stats.downloadSpeedBytesPerSecond.first(),
                "a local file is not loaded over the network",
            )
        }
    }

    @Test
    fun `throttled http - reported speed matches the bandwidth limit, then idles at zero`() {
        if (!prepareOrSkip()) return
        val clip = generateClip() ?: run { skip("ffmpeg unavailable or clip generation failed"); return }
        val limit = clip.length() / THROTTLED_DOWNLOAD_SECONDS
        RangeFileServer(clip, bytesPerSecond = limit).use { server ->
            withPlayer { player, stats ->
                player.setMediaData(UriMediaData(server.url), playWhenReady = false)
                assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
                val durationMillis = checkNotNull(player.mediaProperties.value?.durationMillis)

                // A speed is reported once the cache starts filling.
                withTimeout(15_000) { stats.downloadSpeedBytesPerSecond.first { it > 0 } }

                // Over a few seconds of steady transfer the peak reported speed sits near the
                // server's limit: mpv averages over 1 s, the server throttles per 16 KiB chunk.
                var peak = 0L
                repeat(6) {
                    delay(500)
                    peak = maxOf(peak, stats.downloadSpeedBytesPerSecond.first())
                }
                assertTrue(
                    peak in (limit / 3)..(limit * 2),
                    "peak reported speed $peak B/s should be near the bandwidth limit $limit B/s",
                )

                // Fully cached: nothing more to read, the speed settles at 0 rather than unknown.
                val buffering = checkNotNull(player.features[Buffering])
                withTimeout(60_000) { buffering.bufferedPositionMillis.first { it >= durationMillis - END_SLACK_MILLIS } }
                withTimeout(10_000) { stats.downloadSpeedBytesPerSecond.first { it == 0L } }
            }
        }
    }

    @Test
    fun `reset to unknown on stop`() {
        if (!prepareOrSkip()) return
        val clip = generateClip() ?: run { skip("ffmpeg unavailable or clip generation failed"); return }
        RangeFileServer(clip).use { server ->
            withPlayer { player, stats ->
                player.playUri(server.url)
                withTimeout(15_000) { stats.downloadSpeedBytesPerSecond.first { it >= 0 } }

                player.stopPlayback()
                withTimeout(10_000) { player.state.first { it.mediaStatus == MediaStatus.Idle } }
                assertEquals(NetworkStats.UNKNOWN_SPEED, stats.downloadSpeedBytesPerSecond.first())
            }
        }
    }

    private companion object {
        const val TAG = "MpvNetworkStatsTest"
        const val CLIP_SECONDS = 30
        const val THROTTLED_DOWNLOAD_SECONDS = 12L
        const val END_SLACK_MILLIS = 1_500L
    }
}
