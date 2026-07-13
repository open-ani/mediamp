/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.features.FramePreview
import org.openani.mediamp.features.PreviewFrame
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration test for [MpvFramePreview] against a real libmpv (dev-native JNI build).
 *
 * Uses a video that is solid red for 0.0-2.5s and solid blue for 2.5-5.0s, so the extracted
 * frame's dominant color proves WHICH position was decoded, not just that decoding worked.
 *
 * Skipped when the native runtime or ffmpeg is unavailable (same policy as
 * [MpvMediampPlayerSmokeTest]). Run manually:
 *   ./gradlew :mediamp-mpv:desktopTest --tests "org.openani.mediamp.mpv.MpvFramePreviewTest"
 */
@OptIn(ExperimentalMediampApi::class)
class MpvFramePreviewTest {

    private fun devNativeDir(): File? =
        System.getProperty("mediamp.mpv.dev.native.dir")
            ?.let(::File)
            ?.takeIf {
                it.resolve("libmediampv.dylib").isFile || it.resolve("libmediampv.so").isFile ||
                        it.resolve("mediampv.dll").isFile
            }

    private fun skip(reason: String): Boolean {
        System.err.println("[MpvFramePreviewTest] setup skipped: $reason")
        check(System.getProperty("mediamp.mpv.test.required") != "true") {
            "mpv frame preview tests are required on this runner but would be skipped: $reason"
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
        if (generateColorVideo() == null) return skip("ffmpeg unavailable or color video generation failed")
        return true
    }

    @Test
    fun `preview frames follow requested position - uri media`() {
        if (!prepareOrSkip()) return
        runScenario(UriMediaData(generateColorVideo()!!.absolutePath, emptyMap(), MediaExtraFiles.EMPTY))
    }

    @Test
    fun `preview frames follow requested position - seekable input media`() {
        if (!prepareOrSkip()) return
        runScenario(FileSeekableInputMediaData(generateColorVideo()!!))
    }

    /**
     * Regression: the UI drives requests with `collectLatest`, cancelling the in-flight grab on
     * every scrub movement. Cancellation must NOT tear down the preview session (or the decoder
     * gets destroyed and rebuilt on every movement and never delivers a frame).
     */
    @Test
    fun `cancelled requests do not destroy the session`() {
        if (!prepareOrSkip()) return
        val video = generateColorVideo()!!
        runBlocking(Dispatchers.Default) {
            val player = MpvMediampPlayer(Any(), coroutineContext)
            try {
                player.setMediaData(UriMediaData(video.absolutePath, emptyMap(), MediaExtraFiles.EMPTY))
                val preview = assertNotNull(player.features[FramePreview.Key])

                // Storm of quickly-cancelled requests, like fast scrubbing.
                repeat(6) { i ->
                    val job = launch { preview.getPreviewFrame(500L * i, 160, 160) }
                    delay(80)
                    job.cancel()
                    job.join()
                }

                // A subsequent request must still succeed, quickly (session survived).
                val elapsed = kotlin.system.measureTimeMillis {
                    val blue = assertNotNull(
                        preview.getPreviewFrame(4_000, 160, 160),
                        "no frame after cancellation storm",
                    )
                    assertDominantColor(blue, "blue") { r, _, b -> b > 180 && r < 80 }
                }
                assertTrue(elapsed < 5_000, "grab after cancellation storm took ${elapsed}ms")
            } finally {
                player.close()
            }
        }
    }

    /**
     * Regression: seek-landing detection must not require time-pos to end up near the target.
     * With `absolute+keyframes` the decoder snaps to the previous keyframe; this video has its
     * only keyframe at t=0, so a request at 4s lands ~4s away from the target. The old check
     * (|time-pos - target| < 3s) permanently failed for such positions.
     */
    @Test
    fun `long-GOP media - positions far from a keyframe still produce frames`() {
        if (!prepareOrSkip()) return
        val video = generateLongGopVideo() ?: run {
            skip("ffmpeg unavailable or long-GOP video generation failed")
            return
        }
        runBlocking(Dispatchers.Default) {
            val player = MpvMediampPlayer(Any(), coroutineContext)
            try {
                player.setMediaData(UriMediaData(video.absolutePath, emptyMap(), MediaExtraFiles.EMPTY))
                val preview = assertNotNull(player.features[FramePreview.Key])
                val frame = assertNotNull(
                    preview.getPreviewFrame(4_000, 160, 160),
                    "no frame at 4s on long-GOP media",
                )
                assertDominantColor(frame, "red") { r, _, b -> r > 180 && b < 80 }
            } finally {
                player.close()
            }
        }
    }

    /**
     * Contract: frames must fit within the maxWidth/maxHeight of EACH request. The session used
     * to size its surface from the first request only, so a later smaller request received
     * oversized frames.
     */
    @Test
    fun `smaller max size in a later request shrinks the frame`() {
        if (!prepareOrSkip()) return
        val video = generateColorVideo()!!
        runBlocking(Dispatchers.Default) {
            val player = MpvMediampPlayer(Any(), coroutineContext)
            try {
                player.setMediaData(UriMediaData(video.absolutePath, emptyMap(), MediaExtraFiles.EMPTY))
                val preview = assertNotNull(player.features[FramePreview.Key])

                val big = assertNotNull(preview.getPreviewFrame(1_000, 160, 160), "no frame at 160x160")
                assertTrue(big.width <= 160 && big.height <= 160, "unexpected size ${big.width}x${big.height}")

                val small = assertNotNull(preview.getPreviewFrame(1_000, 64, 64), "no frame at 64x64")
                assertTrue(
                    small.width <= 64 && small.height <= 64,
                    "frame ${small.width}x${small.height} exceeds the requested 64x64 bounds",
                )
                assertDominantColor(small, "red") { r, _, b -> r > 180 && b < 80 }
            } finally {
                player.close()
            }
        }
    }

    /** Audio-only media must fail fast (no-video detection) and cache the verdict. */
    @Test
    fun `audio-only media returns null quickly and caches the result`() {
        if (!prepareOrSkip()) return
        val audio = generateAudioOnlyMedia() ?: run {
            skip("ffmpeg unavailable or audio-only media generation failed")
            return
        }
        runBlocking(Dispatchers.Default) {
            val player = MpvMediampPlayer(Any(), coroutineContext)
            try {
                player.setMediaData(UriMediaData(audio.absolutePath, emptyMap(), MediaExtraFiles.EMPTY))
                val preview = assertNotNull(player.features[FramePreview.Key])

                val first = kotlin.system.measureTimeMillis {
                    assertNull(preview.getPreviewFrame(1_000, 160, 160), "audio-only media produced a frame")
                }
                // The no-video-track detection kicks in once the demuxer opens the file; it must
                // not sit out the full 10s first-frame timeout.
                assertTrue(first < 8_000, "no-video detection took ${first}ms")

                val second = kotlin.system.measureTimeMillis {
                    assertNull(preview.getPreviewFrame(2_000, 160, 160))
                }
                assertTrue(second < 1_000, "no-video verdict should be cached, took ${second}ms")
            } finally {
                player.close()
            }
        }
    }

    private fun runScenario(mediaData: MediaData) {
        runBlocking(Dispatchers.Default) {
            val player = MpvMediampPlayer(Any(), coroutineContext)
            try {
                player.setMediaData(mediaData)
                val preview = assertNotNull(player.features[FramePreview.Key], "FramePreview feature missing")

                // Main player is NOT started: previews must work while the video is merely loaded.
                val red = assertNotNull(
                    preview.getPreviewFrame(1_000, 160, 160),
                    "no frame at 1s",
                )
                assertDominantColor(red, "red") { r, _, b -> r > 180 && b < 80 }
                assertTrue(red.width in 2..160 && red.height in 2..160, "unexpected size ${red.width}x${red.height}")

                val blue = assertNotNull(
                    preview.getPreviewFrame(4_000, 160, 160),
                    "no frame at 4s",
                )
                assertDominantColor(blue, "blue") { r, _, b -> b > 180 && r < 80 }

                // Scrub back: seeking backwards while paused must work too.
                val redAgain = assertNotNull(
                    preview.getPreviewFrame(1_500, 160, 160),
                    "no frame at 1.5s (backwards seek)",
                )
                assertDominantColor(redAgain, "red (backwards)") { r, _, b -> r > 180 && b < 80 }
            } finally {
                player.close()
            }
        }
    }

    /** Asserts on the average color of the center 20x20 region. */
    private fun assertDominantColor(
        frame: PreviewFrame,
        what: String,
        predicate: (r: Int, g: Int, b: Int) -> Boolean,
    ) {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        val cx = frame.width / 2
        val cy = frame.height / 2
        val half = minOf(10, frame.width / 2, frame.height / 2)
        for (y in cy - half until cy + half) {
            for (x in cx - half until cx + half) {
                val argb = frame.pixels[y * frame.width + x]
                r += (argb shr 16) and 0xFF
                g += (argb shr 8) and 0xFF
                b += argb and 0xFF
                n++
            }
        }
        val avgR = (r / n).toInt()
        val avgG = (g / n).toInt()
        val avgB = (b / n).toInt()
        assertTrue(
            predicate(avgR, avgG, avgB),
            "expected $what frame, got avg color ($avgR, $avgG, $avgB) " +
                    "for ${frame.width}x${frame.height} frame at ${frame.positionMillis}ms",
        )
    }

    @OptIn(ExperimentalMediampApi::class)
    private class FileSeekableInputMediaData(
        private val file: File,
        /** Artificial latency per read, to widen decode/seek timing windows in race tests. */
        private val readDelayMillis: Long = 0,
    ) : SeekableInputMediaData {
        override val uri: String get() = "test://${file.name}"
        override val extraFiles: MediaExtraFiles get() = MediaExtraFiles.EMPTY
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
                override fun seekTo(position: Long) = raf.seek(position)
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (readDelayMillis > 0) Thread.sleep(readDelayMillis)
                    return raf.read(buffer, offset, length)
                }

                override fun close() = raf.close()
            }
        }
    }

    /** 0.0s-2.5s solid red, 2.5s-5.0s solid blue — known pixel content for frame assertions. */
    private fun generateColorVideo(): File? {
        val target = File(System.getProperty("java.io.tmpdir"), "mediamp-mpv-frame-preview-colors.mp4")
        if (target.isFile && target.length() > 0) return target
        val ffmpeg = findFfmpeg() ?: return null
        val process = ProcessBuilder(
            ffmpeg, "-y",
            "-f", "lavfi", "-i", "color=c=red:size=640x360:rate=30:duration=2.5",
            "-f", "lavfi", "-i", "color=c=blue:size=640x360:rate=30:duration=2.5",
            "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0,format=yuv420p[v]",
            "-map", "[v]", "-c:v", "libx264", "-preset", "ultrafast", "-g", "15",
            target.absolutePath,
        ).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        return target
    }

    /**
     * Regression (fast-scrub wrong thumbnail): a request following a CANCELLED in-flight
     * request must return its OWN position's frame. Cancelling the caller does not cancel
     * mpv's seek, so the previous request's seek/render can still be in flight when the
     * next request starts; if completion detection attributes that activity to the new
     * request, it returns the previous position's frame labeled with the new target.
     *
     * Note: the misattribution window depends on mpv-internal timing and does not reliably
     * reproduce in-process even with slowed reads (the demuxer cache absorbs most of the
     * latency), so this test is a functional guard for the cancel-then-grab sequence rather
     * than a deterministic reproducer of the race.
     */
    @Test
    fun `frame after a cancelled request matches the new position`() {
        if (!prepareOrSkip()) return
        val video = generateColorVideo()!!
        runBlocking(Dispatchers.Default) {
            val player = MpvMediampPlayer(Any(), coroutineContext)
            try {
                // Slow reads stretch each seek to >100ms so a cancelled request's seek is
                // reliably still in flight when the next request starts; with instant local
                // reads the race window is only microseconds wide and the test cannot see it.
                player.setMediaData(FileSeekableInputMediaData(video, readDelayMillis = 10))
                val preview = assertNotNull(player.features[FramePreview.Key])
                // Warm the session so iteration timing is dominated by the seeks themselves.
                assertNotNull(preview.getPreviewFrame(1_000, 160, 160))

                repeat(8) { i ->
                    // Seek towards the blue zone and cancel while the seek is in flight;
                    // vary the cancellation point across iterations.
                    val job = launch { preview.getPreviewFrame(4_000, 160, 160) }
                    delay(40L + (i % 4) * 20L)
                    job.cancel()
                    job.join()

                    val frame = assertNotNull(
                        preview.getPreviewFrame(1_000, 160, 160),
                        "no frame in iteration $i",
                    )
                    assertDominantColor(frame, "red (iteration $i)") { r, _, b -> r > 180 && b < 80 }
                }
            } finally {
                player.close()
            }
        }
    }

    /** 5s of solid red with keyframes only at t=0 (keyint 300, scenecut off). */
    private fun generateLongGopVideo(): File? {
        val target = File(System.getProperty("java.io.tmpdir"), "mediamp-mpv-frame-preview-longgop.mp4")
        if (target.isFile && target.length() > 0) return target
        val ffmpeg = findFfmpeg() ?: return null
        val process = ProcessBuilder(
            ffmpeg, "-y",
            "-f", "lavfi", "-i", "color=c=red:size=640x360:rate=30:duration=5",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast",
            "-x264-params", "keyint=300:min-keyint=300:scenecut=0",
            target.absolutePath,
        ).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        return target
    }

    /** 3s sine tone, no video track. */
    private fun generateAudioOnlyMedia(): File? {
        val target = File(System.getProperty("java.io.tmpdir"), "mediamp-mpv-frame-preview-audio.m4a")
        if (target.isFile && target.length() > 0) return target
        val ffmpeg = findFfmpeg() ?: return null
        val process = ProcessBuilder(
            ffmpeg, "-y",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
            "-c:a", "aac",
            target.absolutePath,
        ).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        return target
    }

    // Same discovery as MpvMediampPlayerSmokeTest. Deliberately NOT the ffmpeg shipped in
    // the assembled runtime: that one is an LGPL build without libx264, so it can decode
    // but not generate the H.264 test videos.
    private fun findFfmpeg(): String? =
        listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg", "ffmpeg", "ffmpeg.exe")
            .firstOrNull { runCatching { ProcessBuilder(it, "-version").start().waitFor() }.getOrNull() == 0 }
}
