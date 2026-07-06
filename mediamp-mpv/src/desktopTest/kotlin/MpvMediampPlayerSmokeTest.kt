/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.Screenshots
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration smoke test against a real libmpv (Homebrew) using the dev-native JNI build.
 * Skipped when the native runtime or ffmpeg (for test video generation) is unavailable.
 */
class MpvMediampPlayerSmokeTest {

    private fun devNativeDir(): File? =
        System.getProperty("mediamp.mpv.dev.native.dir")
            ?.let(::File)
            ?.takeIf { it.resolve("libmediampv.dylib").isFile || it.resolve("libmediampv.so").isFile }

    private fun prepareOrSkip(): Boolean {
        if (!System.getProperty("os.name").contains("Mac")) {
            println("[SmokeTest] skipped: not macOS")
            return false
        }
        val dir = devNativeDir()
        if (dir == null) {
            println(
                "[SmokeTest] skipped: dev native dir not usable " +
                        "(mediamp.mpv.dev.native.dir=${System.getProperty("mediamp.mpv.dev.native.dir")})",
            )
            return false
        }
        runCatching { MpvMediampPlayer.prepareLibraries(dir.absolutePath, extractRuntimeLibrary = false) }
            .onFailure {
                println("[SmokeTest] skipped: prepareLibraries failed: $it")
                return false
            }
        return true
    }

    private fun findFfmpeg(): String? =
        listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "ffmpeg")
            .firstOrNull { runCatching { ProcessBuilder(it, "-version").start().waitFor() }.getOrNull() == 0 }

    private fun runFfmpeg(vararg args: String): Boolean {
        val ffmpeg = findFfmpeg() ?: return false
        val process = ProcessBuilder(ffmpeg, "-y", *args)
            .redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        return process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0
    }

    private fun generateTestVideo(): File? {
        val target = File(System.getProperty("java.io.tmpdir"), "mediamp-mpv-test-video.mp4")
        if (target.isFile && target.length() > 0) return target
        val ok = runFfmpeg(
            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30,format=yuv420p",
            "-t", "5", "-c:v", "libx264", "-preset", "ultrafast",
            target.absolutePath,
        )
        return target.takeIf { ok }
    }

    /**
     * 0.0s-2.5s solid red, 2.5s-5.0s solid blue — known pixel content for screenshot assertions.
     */
    private fun generateColorVideo(): File? {
        val target = File(System.getProperty("java.io.tmpdir"), "mediamp-mpv-test-colors.mp4")
        if (target.isFile && target.length() > 0) return target
        val ok = runFfmpeg(
            "-f", "lavfi", "-i", "color=c=red:size=640x360:rate=30:duration=2.5",
            "-f", "lavfi", "-i", "color=c=blue:size=640x360:rate=30:duration=2.5",
            "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0,format=yuv420p[v]",
            "-map", "[v]", "-c:v", "libx264", "-preset", "ultrafast",
            target.absolutePath,
        )
        return target.takeIf { ok }
    }

    /**
     * With vo=libmpv, frames are only consumed when a render context drains them; without
     * one, playback never advances. The native render thread does all rendering; this
     * just configures a headless buffer ring (device ptr 0 = system default MTLDevice)
     * so the real Metal render path is exercised.
     */
    @OptIn(InternalMediampApi::class)
    private fun startHeadlessRenderer(player: MpvMediampPlayer): AutoCloseable {
        val handle = player.impl as MPVHandle
        check(player.createMacosRenderContext()) { "createMacosRenderContext failed" }
        check(nSetSurfaceConfigMacos(handle.ptr, 640, 360, 0L)) { "nSetSurfaceConfigMacos failed" }
        return AutoCloseable {
            nSetSurfaceConfigMacos(handle.ptr, 0, 0, 0L)
            player.releaseMacosRenderContext()
        }
    }

    private suspend fun StateFlow<PlaybackState>.await(state: PlaybackState, timeoutMillis: Long = 10_000) {
        withTimeout(timeoutMillis) {
            collectUntil { it == state }
        }
    }

    private suspend fun <T> StateFlow<T>.collectUntil(predicate: (T) -> Boolean) {
        if (predicate(value)) return
        var done = false
        collectWhile { value ->
            if (predicate(value)) done = true
            !done
        }
    }

    private suspend fun <T> StateFlow<T>.collectWhile(predicate: (T) -> Boolean) {
        try {
            collect { if (!predicate(it)) throw StopCollecting }
        } catch (e: StopCollecting) {
            // done
        }
    }

    private object StopCollecting : Exception() {
        private fun readResolve(): Any = StopCollecting
    }

    @OptIn(InternalMediampApi::class, ExperimentalMediampApi::class)
    @Test
    fun `uri playback - state machine, seek, features`() {
        if (!prepareOrSkip()) return
        val video = generateTestVideo() ?: return

        runBlocking(Dispatchers.Default) {
            val player = MpvMediampPlayer(Any(), coroutineContext)
            val renderer = startHeadlessRenderer(player)
            try {
                player.setMediaData(UriMediaData(video.absolutePath, emptyMap(), MediaExtraFiles.EMPTY))
                assertEquals(PlaybackState.READY, player.playbackState.value)

                player.resume()
                player.playbackState.await(PlaybackState.PLAYING)

                // Position should advance.
                withTimeout(10_000) {
                    player.currentPositionMillis.collectUntil { it > 200 }
                }
                val handle = player.impl as MPVHandle
                assertNotNull(handle.getPropertyString("hwdec-current"), "hwdec-current should be queryable")

                // Metadata: at least one audio-less video track list refresh happened without crash.
                val metadata = player.features[MediaMetadata]
                assertNotNull(metadata)

                // Playback speed via feature.
                val speed = assertNotNull(player.features[PlaybackSpeed.Key])
                speed.set(1.5f)
                assertEquals(1.5f, speed.value)

                // Seek: optimistic position + eventual convergence.
                player.seekTo(3_000)
                assertEquals(3_000, player.getCurrentPositionMillis())
                withTimeout(10_000) {
                    player.currentPositionMillis.collectUntil { it in 2_500..5_500 }
                }

                player.pause()
                player.playbackState.await(PlaybackState.PAUSED)

                player.resume()
                player.playbackState.await(PlaybackState.PLAYING)

                player.stopPlayback()
                player.playbackState.await(PlaybackState.FINISHED)
            } finally {
                renderer.close()
                player.close()
            }
            player.playbackState.await(PlaybackState.DESTROYED, 5_000)
        }
    }

    /** Average color of the center 20x20 region of the current frame, read back via [Screenshots]. */
    private suspend fun captureCenterColor(screenshots: Screenshots): Triple<Int, Int, Int> {
        val file = File.createTempFile("mediamp-mpv-shot", ".png")
        try {
            screenshots.takeScreenshot(file.absolutePath)
            val image = assertNotNull(javax.imageio.ImageIO.read(file), "screenshot PNG should be readable")
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            val cx = image.width / 2
            val cy = image.height / 2
            for (x in cx - 10 until cx + 10) {
                for (y in cy - 10 until cy + 10) {
                    val rgb = image.getRGB(x, y)
                    r += (rgb shr 16) and 0xFF; g += (rgb shr 8) and 0xFF; b += rgb and 0xFF; n++
                }
            }
            return Triple((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        } finally {
            file.delete()
        }
    }

    /** Polls screenshots until [predicate] matches — the render thread lags a frame or two behind. */
    private suspend fun awaitCenterColor(
        screenshots: Screenshots,
        what: String,
        predicate: (Triple<Int, Int, Int>) -> Boolean,
    ): Triple<Int, Int, Int> {
        var last: Triple<Int, Int, Int>? = null
        return withTimeout(10_000) {
            while (true) {
                val color = captureCenterColor(screenshots)
                if (predicate(color)) return@withTimeout color
                last = color
                kotlinx.coroutines.delay(200)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable, last=$last")
        }
    }

    /**
     * Pixel-level verification: the real Metal render path must produce frames whose
     * content matches the source video, and seeking must update the rendered frame.
     */
    @OptIn(InternalMediampApi::class, ExperimentalMediampApi::class)
    @Test
    fun `screenshot pixel verification - frame content matches source and updates on seek`() {
        if (!prepareOrSkip()) return
        val video = generateColorVideo() ?: return

        runBlocking(Dispatchers.Default) {
            val player = MpvMediampPlayer(Any(), coroutineContext)
            val renderer = startHeadlessRenderer(player)
            try {
                player.setMediaData(UriMediaData(video.absolutePath, emptyMap(), MediaExtraFiles.EMPTY))
                player.resume()
                player.playbackState.await(PlaybackState.PLAYING)
                // Stay well inside the red segment (0.0-2.5s).
                withTimeout(10_000) {
                    player.currentPositionMillis.collectUntil { it in 300..1_800 }
                }
                val screenshots = assertNotNull(player.features[Screenshots.Key])

                // yuv420 + h264 round-trip is lossy; assert dominance, not exact values.
                val red = awaitCenterColor(screenshots, "red") { (r, _, b) -> r > 180 && b < 80 }
                assertTrue(red.first > 180 && red.third < 80, "expected red frame, got $red")

                // Seek into the blue segment; the rendered frame must follow.
                player.seekTo(4_000)
                withTimeout(10_000) {
                    player.currentPositionMillis.collectUntil { it in 3_000..5_500 }
                }
                val blue = awaitCenterColor(screenshots, "blue") { (r, _, b) -> b > 180 && r < 80 }
                assertTrue(blue.third > 180 && blue.first < 80, "expected blue frame after seek, got $blue")

                player.stopPlayback()
                player.playbackState.await(PlaybackState.FINISHED)
            } finally {
                renderer.close()
                player.close()
            }
        }
    }

    @OptIn(InternalMediampApi::class)
    @Test
    fun `seekable input playback via stream_cb`() {
        if (!prepareOrSkip()) return
        val video = generateTestVideo() ?: return

        runBlocking(Dispatchers.Default) {
            val player = MpvMediampPlayer(Any(), coroutineContext)
            val renderer = startHeadlessRenderer(player)
            try {
                player.setMediaData(FileSeekableInputMediaData(video))
                assertEquals(PlaybackState.READY, player.playbackState.value)

                player.resume()
                player.playbackState.await(PlaybackState.PLAYING)
                withTimeout(10_000) {
                    player.currentPositionMillis.collectUntil { it > 200 }
                }

                // Seeking exercises stream_cb seek_fn.
                player.seekTo(2_000)
                withTimeout(10_000) {
                    player.currentPositionMillis.collectUntil { it in 1_500..4_500 }
                }

                player.stopPlayback()
                player.playbackState.await(PlaybackState.FINISHED)
            } finally {
                renderer.close()
                player.close()
            }
        }
    }

    @OptIn(ExperimentalMediampApi::class)
    private class FileSeekableInputMediaData(private val file: File) : SeekableInputMediaData {
        override val uri: String get() = "test://${file.name}"
        override val extraFiles: MediaExtraFiles get() = MediaExtraFiles.EMPTY
        override val options: List<String> get() = emptyList()
        override fun fileLength(): Long = file.length()
        override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput =
            RandomAccessFileSeekableInput(RandomAccessFile(file, "r"))

        override fun close() {}
    }

    private class RandomAccessFileSeekableInput(private val raf: RandomAccessFile) : SeekableInput {
        override val position: Long get() = raf.filePointer
        override val size: Long get() = raf.length()
        override val bytesRemaining: Long get() = size - position

        override fun seekTo(position: Long) {
            raf.seek(position)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            raf.read(buffer, offset, length)

        override fun close() = raf.close()
    }
}
