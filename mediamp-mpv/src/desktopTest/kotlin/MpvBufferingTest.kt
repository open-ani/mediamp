/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(InternalMediampApi::class)

package org.openani.mediamp.mpv

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.mpv.utils.MpvTestMedia
import org.openani.mediamp.mpv.utils.RangeFileServer
import org.openani.mediamp.playUri
import org.openani.mediamp.source.UriMediaData
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [Buffering.bufferedPositionMillis] against a real libmpv, driven by mpv's
 * `demuxer-cache-time` property.
 *
 * Covered:
 *
 * - unknown before any media is open; reset to unknown on stop, on close, and when another
 *   media is opened (no stale value from the previous media);
 * - local file: a position on the media timeline at or ahead of the playhead;
 * - HTTP source (mpv enables its stream cache for network media): the short clip ends up
 *   fully buffered so the value reaches the media duration; a seek inside the buffered range
 *   keeps it; reaching [MediaStatus.Ended] keeps it (no reset without stop);
 * - bandwidth-limited HTTP source: the value keeps growing while paused (the cache fills
 *   independently of playback) and a seek past the buffered range makes mpv fetch from the
 *   new position, after which the value covers the seek target.
 *
 * Skipped (or failed under `mediamp.mpv.test.required=true`) when the native runtime or
 * ffmpeg is unavailable, like the other real-libmpv tests in this source set. Fixtures
 * (clip generation, HTTP range server) live in [MpvTestMedia] and [RangeFileServer].
 */
class MpvBufferingTest {

    private fun prepareOrSkip(): Boolean = MpvTestMedia.prepareOrSkip(TAG)

    private fun skip(reason: String): Boolean = MpvTestMedia.skip(TAG, reason)

    private fun generateClip(): File? = MpvTestMedia.generateClip(CLIP_SECONDS)

    private fun withPlayer(block: suspend (player: MpvMediampPlayer, buffering: Buffering) -> Unit) {
        // A dedicated single-thread dispatcher stands in for the UI thread: the machine is
        // confined to it, and this test body runs on it too, so command-thread rules hold.
        // It must own exactly one physical thread: `limitedParallelism(1)` only serializes,
        // and the player's main-thread check compares thread identity across suspensions.
        val mainDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mpv-buffering-test-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        try {
            runBlocking(mainDispatcher) {
                val player = MpvMediampPlayer(Any(), coroutineContext, mainDispatcher = mainDispatcher)
                try {
                    // Audio device drain is not under test; see MpvHeadlessEofTest.
                    (player.impl as MPVHandle).setPropertyString("ao", "null")
                    val buffering = checkNotNull(player.features[Buffering]) { "mpv player must expose Buffering" }
                    block(player, buffering)
                } finally {
                    player.close()
                }
            }
        } finally {
            mainDispatcher.close()
        }
    }

    private suspend fun MpvMediampPlayer.awaitPositionAtLeast(millis: Long): Long =
        withTimeout(15_000) { currentPositionMillis.first { it >= millis } }

    private suspend fun Buffering.awaitBufferedAtLeast(millis: Long, timeoutMillis: Long = 30_000): Long =
        withTimeout(timeoutMillis) { bufferedPositionMillis.first { it >= millis } }

    private fun assertNotBehindPlayhead(buffered: Long, position: Long) {
        assertTrue(
            buffered >= position - POSITION_SLACK_MILLIS,
            "buffered position $buffered must not be behind the playhead $position",
        )
    }

    private suspend fun MpvMediampPlayer.awaitIdleAfterStop() {
        stopPlayback()
        withTimeout(10_000) { state.first { it.mediaStatus == MediaStatus.Idle } }
    }

    // region unknown / reset

    @Test
    fun `unknown before any media is open`() {
        if (!prepareOrSkip()) return
        withPlayer { _, buffering ->
            assertEquals(Buffering.UNKNOWN_POSITION, buffering.bufferedPositionMillis.first())
        }
    }

    @Test
    fun `reset to unknown on stop`() {
        if (!prepareOrSkip()) return
        val clip = generateClip() ?: run { skip("ffmpeg unavailable or clip generation failed"); return }
        withPlayer { player, buffering ->
            player.playUri(clip.absolutePath)
            buffering.awaitBufferedAtLeast(1)

            player.awaitIdleAfterStop()
            assertEquals(Buffering.UNKNOWN_POSITION, buffering.bufferedPositionMillis.first())
        }
    }

    @Test
    fun `reset to unknown on close`() {
        if (!prepareOrSkip()) return
        val clip = generateClip() ?: run { skip("ffmpeg unavailable or clip generation failed"); return }
        withPlayer { player, buffering ->
            player.playUri(clip.absolutePath)
            buffering.awaitBufferedAtLeast(1)

            player.close()
            withTimeout(10_000) { player.state.first { it.mediaStatus == MediaStatus.Released } }
            assertEquals(Buffering.UNKNOWN_POSITION, buffering.bufferedPositionMillis.first())
        }
    }

    @Test
    fun `opening another media does not keep the previous buffered position`() {
        if (!prepareOrSkip()) return
        val clip = generateClip() ?: run { skip("ffmpeg unavailable or clip generation failed"); return }
        RangeFileServer(clip).use { server ->
            withPlayer { player, buffering ->
                // The whole clip gets cached from HTTP, so the value sits at the duration.
                player.playUri(server.url)
                val durationMillis = checkNotNull(player.mediaProperties.value?.durationMillis)
                val fullyBuffered = buffering.awaitBufferedAtLeast(durationMillis - END_SLACK_MILLIS)

                // Reopen the same clip from the local file system, paused at 0: mpv only reads
                // a short way ahead of the playhead, far less than the previous ~full-length
                // value, which therefore cannot survive the reopen.
                player.setMediaData(UriMediaData(clip.absolutePath), playWhenReady = false)
                assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
                val afterReopen = withTimeout(15_000) {
                    buffering.bufferedPositionMillis.first { it != fullyBuffered }
                }
                assertTrue(
                    afterReopen < fullyBuffered - 5_000,
                    "buffered position after reopen ($afterReopen) must not carry over the previous media's value ($fullyBuffered)",
                )
            }
        }
    }

    // endregion

    // region local file

    @Test
    fun `local file - reported at or ahead of playhead while playing`() {
        if (!prepareOrSkip()) return
        val clip = generateClip() ?: run { skip("ffmpeg unavailable or clip generation failed"); return }
        withPlayer { player, buffering ->
            player.playUri(clip.absolutePath)
            assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)

            player.awaitPositionAtLeast(1_000)
            val buffered = buffering.awaitBufferedAtLeast(1)
            assertNotBehindPlayhead(buffered, player.currentPositionMillis.value)

            // Keeps tracking the playhead as playback advances.
            player.awaitPositionAtLeast(3_000)
            val later = buffering.awaitBufferedAtLeast(3_000 - POSITION_SLACK_MILLIS)
            assertNotBehindPlayhead(later, player.currentPositionMillis.value)
        }
    }

    // endregion

    // region http (unlimited bandwidth)

    @Test
    fun `http - ahead of playhead and reaches duration`() {
        if (!prepareOrSkip()) return
        val clip = generateClip() ?: run { skip("ffmpeg unavailable or clip generation failed"); return }
        RangeFileServer(clip).use { server ->
            withPlayer { player, buffering ->
                player.playUri(server.url)
                assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
                val durationMillis = checkNotNull(player.mediaProperties.value?.durationMillis)
                assertTrue(
                    durationMillis in (CLIP_SECONDS * 1000L - END_SLACK_MILLIS)..(CLIP_SECONDS * 1000L + END_SLACK_MILLIS),
                    "unexpected duration $durationMillis",
                )

                player.awaitPositionAtLeast(1_000)
                val buffered = buffering.awaitBufferedAtLeast(1)
                assertNotBehindPlayhead(buffered, player.currentPositionMillis.value)

                val atEnd = buffering.awaitBufferedAtLeast(durationMillis - END_SLACK_MILLIS)
                assertTrue(atEnd <= durationMillis + END_SLACK_MILLIS, "buffered position $atEnd exceeds duration $durationMillis")
            }
        }
    }

    @Test
    fun `http - seek inside the buffered range keeps the buffered position`() {
        if (!prepareOrSkip()) return
        val clip = generateClip() ?: run { skip("ffmpeg unavailable or clip generation failed"); return }
        RangeFileServer(clip).use { server ->
            withPlayer { player, buffering ->
                player.playUri(server.url)
                val durationMillis = checkNotNull(player.mediaProperties.value?.durationMillis)
                buffering.awaitBufferedAtLeast(durationMillis - END_SLACK_MILLIS)

                player.seekTo(SEEK_TARGET_MILLIS)
                player.awaitPositionAtLeast(SEEK_TARGET_MILLIS - POSITION_SLACK_MILLIS)
                // Give the cache a moment to report after the seek settles.
                delay(500)
                val afterSeek = buffering.bufferedPositionMillis.first()
                assertTrue(
                    afterSeek >= durationMillis - END_SLACK_MILLIS,
                    "buffered position $afterSeek must still cover the whole clip after seeking to $SEEK_TARGET_MILLIS",
                )
            }
        }
    }

    @Test
    fun `http - reaching Ended keeps the buffered position`() {
        if (!prepareOrSkip()) return
        val clip = generateClip() ?: run { skip("ffmpeg unavailable or clip generation failed"); return }
        RangeFileServer(clip).use { server ->
            withPlayer { player, buffering ->
                player.playUri(server.url)
                val durationMillis = checkNotNull(player.mediaProperties.value?.durationMillis)
                buffering.awaitBufferedAtLeast(durationMillis - END_SLACK_MILLIS)

                player.seekTo(durationMillis - 1_500)
                withTimeout(20_000) { player.state.first { it.mediaStatus == MediaStatus.Ended } }
                val atEnded = buffering.bufferedPositionMillis.first()
                assertNotEquals(Buffering.UNKNOWN_POSITION, atEnded, "Ended must not reset the buffered position")
                assertTrue(
                    atEnded >= durationMillis - END_SLACK_MILLIS,
                    "buffered position $atEnded must still cover the clip at Ended",
                )
            }
        }
    }

    // endregion

    // region http (bandwidth-limited)

    @Test
    fun `throttled http - buffered position keeps growing while paused`() {
        if (!prepareOrSkip()) return
        val clip = generateClip() ?: run { skip("ffmpeg unavailable or clip generation failed"); return }
        RangeFileServer(clip, bytesPerSecond = throttleBytesPerSecond(clip)).use { server ->
            withPlayer { player, buffering ->
                player.setMediaData(UriMediaData(server.url), playWhenReady = false)
                assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
                val durationMillis = checkNotNull(player.mediaProperties.value?.durationMillis)

                val first = buffering.awaitBufferedAtLeast(1)
                assertTrue(first < durationMillis - END_SLACK_MILLIS, "clip must not be fully cached yet, got $first")
                // Paused: the playhead stays put while the cache keeps filling.
                val grown = buffering.awaitBufferedAtLeast(first + 2_000)
                assertEquals(0L, player.currentPositionMillis.value, "playhead must not move while paused")
                assertTrue(grown > first)

                // And it eventually holds the whole clip.
                buffering.awaitBufferedAtLeast(durationMillis - END_SLACK_MILLIS, timeoutMillis = 60_000)
            }
        }
    }

    @Test
    fun `throttled http - seek past the buffered range re-buffers from the seek target`() {
        if (!prepareOrSkip()) return
        val clip = generateClip() ?: run { skip("ffmpeg unavailable or clip generation failed"); return }
        RangeFileServer(clip, bytesPerSecond = throttleBytesPerSecond(clip)).use { server ->
            withPlayer { player, buffering ->
                player.setMediaData(UriMediaData(server.url), playWhenReady = false)
                val durationMillis = checkNotNull(player.mediaProperties.value?.durationMillis)
                val beforeSeek = buffering.awaitBufferedAtLeast(1)
                assertTrue(
                    beforeSeek < SEEK_TARGET_MILLIS - 5_000,
                    "seek target $SEEK_TARGET_MILLIS must lie outside the buffered range, got $beforeSeek",
                )

                player.seekTo(SEEK_TARGET_MILLIS)
                player.awaitPositionAtLeast(SEEK_TARGET_MILLIS - POSITION_SLACK_MILLIS)
                val afterSeek = buffering.awaitBufferedAtLeast(SEEK_TARGET_MILLIS)
                assertTrue(afterSeek <= durationMillis + END_SLACK_MILLIS, "buffered position $afterSeek exceeds duration")
                assertNotBehindPlayhead(afterSeek, player.currentPositionMillis.value)
            }
        }
    }

    // endregion

    /**
     * Bandwidth that downloads the clip in about [THROTTLED_DOWNLOAD_SECONDS]: well above the
     * clip's bitrate (playback never starves) but slow enough that the partially-buffered
     * states are observable.
     */
    private fun throttleBytesPerSecond(clip: File): Long = clip.length() / THROTTLED_DOWNLOAD_SECONDS

    private companion object {
        const val TAG = "MpvBufferingTest"
        const val CLIP_SECONDS = 30
        const val THROTTLED_DOWNLOAD_SECONDS = 12L
        const val SEEK_TARGET_MILLIS = 20_000L

        /** `time-pos` and `demuxer-cache-time` arrive as separate property events. */
        const val POSITION_SLACK_MILLIS = 500L

        /** The last packet timestamps sit slightly before the container duration. */
        const val END_SLACK_MILLIS = 1_500L
    }
}
