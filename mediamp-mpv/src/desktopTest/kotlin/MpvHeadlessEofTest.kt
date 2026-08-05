/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.playUri
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * worked) and natural EOF must transition to [MediaStatus.Ended] with a
 * [PlaybackEvent.MediaEnded] on the events flow (the smoke tests only exercise
 * `stopPlayback`, which yields [MediaStatus.Idle]).
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

        // A dedicated serial dispatcher stands in for the UI thread: the machine is confined
        // to it, and this test body runs on it too, so command-thread rules hold.
        val mainDispatcher = Dispatchers.Default.limitedParallelism(1)
        runBlocking(mainDispatcher) {
            val player = MpvMediampPlayer(
                Any(), coroutineContext,
                mainDispatcher = mainDispatcher,
                isOnMainThread = { true },
            )
            val renderer = if (useSurfaceRing) {
                check(player.createRenderContext()) { "createRenderContext failed" }
                check(player.requestSurface(320, 180, 0L)) { "requestSurface failed" }
                AutoCloseable {
                    player.releaseSurface()
                    player.releaseRenderContext()
                }
            } else null
            try {
                // Route audio to the null output: this test is about the playback clock and
                // EOF state transitions, not audio rendering, and a headless environment's
                // audio device can accept the stream but never consume it (observed on dev
                // Macs: coreaudio opens, samples never drain, playback wedges on frame 1).
                (player.impl as MPVHandle).setPropertyString("ao", "null")

                // Subscribe before issuing commands (events has no replay).
                val endedEvent = async(start = CoroutineStart.UNDISPATCHED) {
                    player.events.filterIsInstance<PlaybackEvent.MediaEnded>().first()
                }

                // playUri autoplays: playWhenReady = true by default.
                player.playUri(video.absolutePath)
                assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
                assertTrue(player.state.value.playWhenReady, "open must apply the requested intent")

                // The position flow (not just the polled property) must advance.
                withTimeout(15_000) {
                    player.currentPositionMillis.first { it > 1_000 }
                }

                // Natural EOF must surface as Ended (status) and MediaEnded (event).
                withTimeout(20_000) {
                    player.state.first { it.mediaStatus == MediaStatus.Ended }
                }
                // I2: entering Ended resets the intent axis in the same emission.
                assertFalse(player.state.value.playWhenReady, "Ended must normalize playWhenReady = false")

                val ended = withTimeout(5_000) { endedEvent.await() }
                assertTrue(
                    (ended.durationMillis ?: 0L) > 3_000,
                    "MediaEnded must carry the media duration, got ${ended.durationMillis}",
                )
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
    fun `position advances and EOF ends - headless without surface`() {
        runPlayToEof(useSurfaceRing = false)
    }

    @Test
    fun `position advances and EOF ends - with surface ring`() {
        runPlayToEof(useSurfaceRing = true)
    }
}
