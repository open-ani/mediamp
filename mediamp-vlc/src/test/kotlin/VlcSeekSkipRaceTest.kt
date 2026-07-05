/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.vlc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Integration reproduction of the user-reported bug: while fast-forwarding with rapid
 * right-arrow presses (= rapid `skip(+5000)` calls), the observed playback position
 * intermittently regresses ("progress bar gets pulled back") and skips get lost.
 *
 * The race needs seeks that take time to complete. A plain local file seeks in
 * microseconds and does not exhibit it (control scenario); a [SeekableInputMediaData]
 * whose input has seek latency — exactly how Animeko feeds torrent/HTTP streams into
 * VLC — reproduces it (repro scenario).
 *
 * Requires a real libvlc (e.g. /Applications/VLC.app on macOS) and ffmpeg on PATH.
 * Run manually:
 *   ./gradlew :mediamp-vlc:test --tests "org.openani.mediamp.vlc.VlcSeekSkipRaceTest"
 */
class VlcSeekSkipRaceTest {

    /** These are real-playback integration tests: skipped when libvlc or ffmpeg is unavailable. */
    private fun assumeRealPlaybackEnvironment() {
        org.junit.Assume.assumeTrue(
            "ffmpeg not found; skipping real-playback test",
            findFfmpeg() != null,
        )
        org.junit.Assume.assumeTrue(
            "libvlc not found; skipping real-playback test",
            runCatching { uk.co.caprica.vlcj.factory.discovery.NativeDiscovery().discover() }.getOrDefault(false),
        )
    }

    @Test
    fun `control - rapid skips on a fast local file`() {
        assumeRealPlaybackEnvironment()
        runScenario(UriMediaData("file://${ensureTestVideo().absolutePath}"))
    }

    @OptIn(ExperimentalMediampApi::class)
    @Test
    fun `repro - rapid skips on a slow seekable input`() {
        assumeRealPlaybackEnvironment()
        runScenario(SlowSeekMediaData(ensureTestVideo(), seekLatencyMillis = 300))
    }

    /**
     * Real anime files have long GOPs: a seek lands mid-GOP and VLC must decode forward
     * from the previous keyframe, which takes real time. Pressing again inside that
     * window is what issue #1238 describes (decoder "no reference clock" errors,
     * swallowed skips, position pull-backs).
     */
    @Test
    fun `repro - very rapid skips on a long-GOP video`() {
        assumeRealPlaybackEnvironment()
        runScenario(
            UriMediaData("file://${ensureHardVideo().absolutePath}"),
            skipCount = 10,
            skipIntervalMillis = 100,
        )
    }

    private fun runScenario(
        mediaData: MediaData,
        skipCount: Int = 6,
        skipIntervalMillis: Long = 250,
    ) {
        val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val log = Collections.synchronizedList(mutableListOf<Pair<Long, String>>())
        val t0 = System.currentTimeMillis()
        fun mark(msg: String) {
            log.add((System.currentTimeMillis() - t0) to msg)
        }

        try {
            runBlocking {
                val player = VlcMediampPlayer(playerScope.coroutineContext)
                val collector = playerScope.launch {
                    player.currentPositionMillis.collect { mark("pos $it") }
                }

                player.setMediaData(mediaData)
                player.resume()
                withTimeout(60_000) {
                    while (player.playbackState.value != PlaybackState.PLAYING ||
                        player.currentPositionMillis.value < 3_000
                    ) {
                        delay(100)
                    }
                }

                val base = player.currentPositionMillis.value
                mark("BASE $base — issuing $skipCount x skip(+5000) every $skipIntervalMillis ms")
                repeat(skipCount) {
                    player.skip(5_000)
                    mark("SKIP#${it + 1}")
                    delay(skipIntervalMillis)
                }
                delay(5_000) // let the last seek settle
                val final = player.currentPositionMillis.value
                mark("FINAL $final (expected ~${base + skipCount * 5_000})")

                collector.cancelAndJoin()
                player.stopPlayback()

                println("=== position/emission timeline ===")
                val snapshot = synchronized(log) { log.toList() }
                for ((t, m) in snapshot) {
                    println("%6d ms  %s".format(t, m))
                }

                // (a) UI-visible pull-back: with only forward skips issued, the observed
                // position stream must never go backwards (small jitter tolerance).
                var maxSeen = 0L
                val regressions = mutableListOf<String>()
                for ((t, m) in snapshot) {
                    if (!m.startsWith("pos ")) continue
                    val v = m.removePrefix("pos ").toLong()
                    if (v < maxSeen - 500) {
                        regressions.add("at ${t}ms position regressed to $v (max seen $maxSeen)")
                    }
                    if (v > maxSeen) maxSeen = v
                }
                regressions.forEach { println("REGRESSION: $it") }

                // (b) lost skips: all forward skips from base must accumulate.
                val expected = base + skipCount * 5_000
                println("accumulation: base=$base final=$final expected=$expected")

                assertTrue(
                    regressions.isEmpty(),
                    "observed position regressed ${regressions.size} time(s):\n" +
                        regressions.joinToString("\n"),
                )
                assertTrue(
                    final >= expected - 2_000,
                    "skips were lost: final=$final, expected >= ${expected - 2_000}",
                )
            }
        } catch (e: Throwable) {
            // print before cleanup: cancelling the scope runs the player's close() completion
            // handler, whose own exceptions would otherwise mask the real failure
            e.printStackTrace()
            throw e
        } finally {
            try {
                playerScope.cancel()
            } catch (e: Throwable) {
                println("(ignored) exception while cancelling player scope: $e")
            }
        }
    }

    /**
     * Feeds a local file through the same callback-media path Animeko uses for
     * torrent/HTTP streams, with [seekLatencyMillis] of artificial latency per seek
     * (≈ waiting for a torrent piece / range request).
     */
    @OptIn(ExperimentalMediampApi::class)
    private class SlowSeekMediaData(
        private val file: File,
        private val seekLatencyMillis: Long,
    ) : SeekableInputMediaData {
        override val uri: String get() = "test://slow-seek/${file.name}"
        override val extraFiles: MediaExtraFiles get() = MediaExtraFiles()
        override val options: List<String> get() = emptyList()
        override fun fileLength(): Long = file.length()
        override fun close() {}

        override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput {
            val raf = RandomAccessFile(file, "r")
            return object : SeekableInput {
                private val fileSize = raf.length()
                override val position: Long get() = raf.filePointer
                override val bytesRemaining: Long get() = fileSize - raf.filePointer
                override val size: Long get() = fileSize

                override fun seekTo(position: Long) {
                    Thread.sleep(seekLatencyMillis) // the piece is not downloaded yet
                    raf.seek(position)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    raf.read(buffer, offset, length)

                override fun close() = raf.close()
            }
        }
    }

    /** 1080p with 20 s GOPs (keyframe only every 600 frames) — seeks are expensive to decode. */
    private fun ensureHardVideo(): File {
        return generateVideo(
            File(System.getProperty("java.io.tmpdir"), "mediamp-seek-race-hard-gop600.mp4"),
            "testsrc2=size=1920x1080:rate=30:duration=300",
            "-g", "600", "-bf", "3", "-b:v", "8M",
        )
    }

    private fun ensureTestVideo(): File {
        return generateVideo(
            File(System.getProperty("java.io.tmpdir"), "mediamp-seek-race-testsrc-300s.mp4"),
            "testsrc2=size=1280x720:rate=30:duration=300",
            "-g", "30",
        )
    }

    private fun findFfmpeg(): String? =
        listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg")
            .firstOrNull { File(it).canExecute() }

    private fun generateVideo(file: File, source: String, vararg extraArgs: String): File {
        if (file.exists() && file.length() > 0) return file
        val ffmpeg = checkNotNull(findFfmpeg()) { "ffmpeg not found" }
        val process = ProcessBuilder(
            ffmpeg, "-y",
            "-f", "lavfi", "-i", source,
            "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
            *extraArgs,
            file.absolutePath,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "ffmpeg failed to generate test video:\n${output.takeLast(2000)}" }
        return file
    }
}
