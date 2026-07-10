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
import kotlin.test.assertTrue

/**
 * Integration test for [VlcFramePreview] against a real libvlc.
 *
 * Uses a video that is solid red for 0.0-2.5s and solid blue for 2.5-5.0s, so the extracted
 * frame's dominant color proves WHICH position was decoded, not just that decoding worked.
 *
 * Requires a real libvlc (e.g. /Applications/VLC.app on macOS) and ffmpeg. Skipped otherwise.
 * Run manually:
 *   ./gradlew :mediamp-vlc:test --tests "org.openani.mediamp.vlc.VlcFramePreviewTest"
 */
@OptIn(ExperimentalMediampApi::class)
class VlcFramePreviewTest {

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
    fun `preview frames follow requested position - uri media`() {
        assumeRealPlaybackEnvironment()
        runScenario(UriMediaData("file://${ensureColorVideo().absolutePath}"))
    }

    @Test
    fun `preview frames follow requested position - seekable input media`() {
        assumeRealPlaybackEnvironment()
        runScenario(FileSeekableInputMediaData(ensureColorVideo()))
    }

    private fun runScenario(mediaData: MediaData) {
        val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                val player = VlcMediampPlayer(playerScope.coroutineContext)
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
        } finally {
            playerScope.cancel()
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
            "expected $what frame, got avg color ($avgR, $avgG, $avgB) for ${frame.width}x${frame.height} frame at ${frame.positionMillis}ms",
        )
    }

    @OptIn(ExperimentalMediampApi::class)
    private class FileSeekableInputMediaData(private val file: File) : SeekableInputMediaData {
        override val uri: String get() = "test://${file.name}"
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
                override fun seekTo(position: Long) = raf.seek(position)
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    raf.read(buffer, offset, length)

                override fun close() = raf.close()
            }
        }
    }

    /** 0.0s-2.5s solid red, 2.5s-5.0s solid blue — known pixel content for frame assertions. */
    private fun ensureColorVideo(): File {
        val target = File(System.getProperty("java.io.tmpdir"), "mediamp-vlc-frame-preview-colors.mp4")
        if (target.isFile && target.length() > 0) return target
        val ffmpeg = checkNotNull(findFfmpeg()) { "ffmpeg not found" }
        val process = ProcessBuilder(
            ffmpeg, "-y",
            "-f", "lavfi", "-i", "color=c=red:size=640x360:rate=30:duration=2.5",
            "-f", "lavfi", "-i", "color=c=blue:size=640x360:rate=30:duration=2.5",
            "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0,format=yuv420p[v]",
            "-map", "[v]", "-c:v", "libx264", "-preset", "ultrafast", "-g", "15",
            target.absolutePath,
        ).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "ffmpeg failed" }
        return target
    }

    private fun findFfmpeg(): String? =
        listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg")
            .firstOrNull { File(it).canExecute() }
}
