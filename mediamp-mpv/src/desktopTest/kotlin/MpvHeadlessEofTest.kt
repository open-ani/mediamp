/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Playback progress and end-of-file state transitions, in both render modes:
 *
 * - fully headless (no surface ring configured — e.g. probing tools that never compose a
 *   surface): the macOS/Windows render thread must drain video frames so playback and
 *   `time-pos` property events keep flowing;
 * - with the headless surface ring (the regular rendered path).
 *
 * In both modes [MpvMediampPlayer.currentPositionMillis] must advance (open-ani/animeko
 * headless probing regression: the position flow stayed at 0 while polling `time-pos`
 * worked) and natural EOF must transition the playback state to [PlaybackState.FINISHED]
 * (the existing smoke tests only reach FINISHED via `stopPlayback`).
 */
class MpvHeadlessEofTest {

    private fun devNativeDir(): File? =
        System.getProperty("mediamp.mpv.dev.native.dir")
            ?.let(::File)
            ?.takeIf {
                it.resolve("libmediampv.dylib").isFile || it.resolve("libmediampv.so").isFile ||
                        it.resolve("mediampv.dll").isFile
            }

    private fun skip(reason: String): Boolean {
        System.err.println("[HeadlessEofTest] setup skipped: $reason")
        check(System.getProperty("mediamp.mpv.test.required") != "true") {
            "mpv headless tests are required on this runner but would be skipped: $reason"
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
     * Short clip with both a video and an audio track: whichever track the headless
     * environment can play (video decode may be unavailable without a GPU context)
     * keeps the playback clock advancing to EOF.
     */
    private fun generateShortAvVideo(): File? {
        val target = File(System.getProperty("java.io.tmpdir"), "mediamp-mpv-test-av-short.mp4")
        if (target.isFile && target.length() > 0) return target
        val ffmpeg = findFfmpeg() ?: return null
        val process = ProcessBuilder(
            ffmpeg, "-y",
            "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-t", "4", "-c:v", "mpeg4", "-q:v", "5", "-c:a", "aac",
            target.absolutePath,
        ).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        return target
    }

    @OptIn(InternalMediampApi::class)
    private fun runPlayToEof(useSurfaceRing: Boolean) {
        if (!prepareOrSkip()) return
        val video = generateShortAvVideo()
            ?: run { skip("ffmpeg unavailable or test video generation failed"); return }

        runBlocking(Dispatchers.Default) {
            val player = MpvMediampPlayer(Any(), coroutineContext)
            val renderer = if (useSurfaceRing) {
                check(player.createRenderContext()) { "createRenderContext failed" }
                check(player.requestSurface(320, 180, 0L)) { "requestSurface failed" }
                AutoCloseable {
                    player.releaseSurface()
                    player.releaseRenderContext()
                }
            } else null
            try {
                player.setMediaData(UriMediaData(video.absolutePath, emptyMap(), MediaExtraFiles.EMPTY))
                player.resume()

                // The position flow (not just the polled property) must advance.
                withTimeout(15_000) {
                    var done = false
                    player.currentPositionMillis.collectWhile {
                        if (it > 1_000) done = true
                        !done
                    }
                }

                // Natural EOF must surface as FINISHED.
                withTimeout(20_000) {
                    var done = false
                    player.playbackState.collectWhile {
                        if (it == PlaybackState.FINISHED) done = true
                        !done
                    }
                }
                assertTrue(
                    player.currentPositionMillis.value > 1_000,
                    "position should stay at the played value after EOF, " +
                            "got ${player.currentPositionMillis.value}",
                )
            } finally {
                renderer?.close()
                player.close()
            }
        }
    }

    @Test
    fun `position advances and EOF finishes - headless without surface`() {
        runPlayToEof(useSurfaceRing = false)
    }

    @Test
    fun `position advances and EOF finishes - with surface ring`() {
        runPlayToEof(useSurfaceRing = true)
    }

    private suspend fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectWhile(predicate: (T) -> Boolean) {
        try {
            collect { if (!predicate(it)) throw StopCollecting }
        } catch (e: StopCollecting) {
            // done
        }
    }

    private object StopCollecting : Exception() {
        private fun readResolve(): Any = StopCollecting
    }
}
