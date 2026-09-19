/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(ExperimentalMediampApi::class, InternalMediampApi::class)

package org.openani.mediamp.mpv

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.playUri
import org.openani.mediamp.source.UriMediaData
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
 * ffmpeg is unavailable, like the other real-libmpv tests in this source set.
 */
class MpvBufferingTest {

    private fun devNativeDir(): File? =
        System.getProperty("mediamp.mpv.dev.native.dir")
            ?.let(::File)
            ?.takeIf {
                it.resolve("libmediampv.dylib").isFile || it.resolve("libmediampv.so").isFile ||
                        it.resolve("mediampv.dll").isFile
            }

    private fun skip(reason: String): Boolean {
        System.err.println("[MpvBufferingTest] setup skipped: $reason")
        check(System.getProperty("mediamp.mpv.test.required") != "true") {
            "mpv buffering tests are required on this runner but would be skipped: $reason"
        }
        return false
    }

    private fun prepareOrSkip(): Boolean {
        val osName = System.getProperty("os.name")
        if (!osName.contains("Mac") && !osName.contains("Windows")) {
            return skip("no desktop render path on $osName")
        }
        val dir = devNativeDir()
            ?: return skip(
                "dev native dir not usable " +
                        "(mediamp.mpv.dev.native.dir=${System.getProperty("mediamp.mpv.dev.native.dir")})",
            )
        runCatching { MpvMediampPlayer.prepareLibraries(dir.absolutePath, extractRuntimeLibrary = false) }
            .onFailure { return skip("prepareLibraries failed: $it") }
        return true
    }

    private fun findFfmpeg(): String? =
        listOfNotNull(
            devNativeDir()?.resolve("ffmpeg.exe")?.absolutePath,
            "/opt/homebrew/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
            "ffmpeg",
            "ffmpeg.exe",
        )
            .firstOrNull { runCatching { ProcessBuilder(it, "-version").start().waitFor() }.getOrNull() == 0 }

    /**
     * A [CLIP_SECONDS] audio+video clip with the `moov` atom up front (`faststart`), so mpv
     * can start decoding from a sequential HTTP read without first seeking to the file end.
     */
    private fun generateClip(): File? {
        val target = File(System.getProperty("java.io.tmpdir"), "mediamp-mpv-test-buffering-${CLIP_SECONDS}s.mp4")
        if (target.isFile && target.length() > 0) return target
        val ffmpeg = findFfmpeg() ?: return null
        val process = ProcessBuilder(
            ffmpeg, "-y",
            "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-t", CLIP_SECONDS.toString(), "-c:v", "mpeg4", "-q:v", "5", "-c:a", "aac",
            "-movflags", "+faststart",
            target.absolutePath,
        ).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        return target
    }

    /**
     * Static file server with HTTP range support, so mpv treats the clip as a seekable
     * network stream (stream cache on). Requests are served concurrently: mpv keeps one
     * connection streaming while probing other byte ranges on another.
     *
     * @param bytesPerSecond optional bandwidth limit per connection, to keep the clip from
     *   being fully cached before the test has observed the partially-buffered states.
     */
    private class RangeFileServer(
        private val file: File,
        private val bytesPerSecond: Long? = null,
    ) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "mpv-test-http").apply { isDaemon = true }
        }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = this@RangeFileServer.executor
            createContext("/clip.mp4") { exchange ->
                val length = file.length()
                val range = exchange.requestHeaders.getFirst("Range")
                    ?.removePrefix("bytes=")
                    ?.split("-", limit = 2)
                val start = range?.getOrNull(0)?.toLongOrNull() ?: 0L
                val end = range?.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (length - 1)
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("Content-Type", "video/mp4")
                val count = end - start + 1
                if (range != null) {
                    exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$length")
                    exchange.sendResponseHeaders(206, count)
                } else {
                    exchange.sendResponseHeaders(200, count)
                }
                try {
                    if (exchange.requestMethod != "HEAD") {
                        file.inputStream().use { input ->
                            input.skipNBytes(start)
                            val buffer = ByteArray(CHUNK_BYTES)
                            var remaining = count
                            exchange.responseBody.use { out ->
                                while (remaining > 0) {
                                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                    out.flush()
                                    remaining -= read
                                    bytesPerSecond?.let { Thread.sleep(read * 1000L / it) }
                                }
                            }
                        }
                    }
                } catch (_: java.io.IOException) {
                    // mpv closes connections it no longer needs (e.g. after a seek); that is
                    // not a server failure.
                } finally {
                    exchange.close()
                }
            }
            start()
        }

        val url: String get() = "http://127.0.0.1:${server.address.port}/clip.mp4"

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }

        private companion object {
            const val CHUNK_BYTES = 16 * 1024
        }
    }

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
                    // Windows selects its backend from a live Skiko redrawer; without a window
                    // the open would suspend forever unless the headless renderer is explicit.
                    check(player.createRenderContext()) { "createRenderContext failed" }
                    // Audio device drain is not under test; see MpvHeadlessEofTest.
                    (player.impl as MPVHandle).setPropertyString("ao", "null")
                    val buffering = checkNotNull(player.features[Buffering]) { "mpv player must expose Buffering" }
                    // An open that never completes must fail the test, not hang the CI job.
                    withTimeout(TEST_TIMEOUT_MILLIS) { block(player, buffering) }
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
        const val CLIP_SECONDS = 30
        const val THROTTLED_DOWNLOAD_SECONDS = 12L
        const val SEEK_TARGET_MILLIS = 20_000L

        /** `time-pos` and `demuxer-cache-time` arrive as separate property events. */
        const val POSITION_SLACK_MILLIS = 500L

        /** The last packet timestamps sit slightly before the container duration. */
        const val END_SLACK_MILLIS = 1_500L

        /** Upper bound for one test body; well above the sum of its individual waits. */
        const val TEST_TIMEOUT_MILLIS = 180_000L
    }
}
