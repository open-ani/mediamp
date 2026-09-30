/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.mpv.internal.MpvSurfaceBackend
import org.openani.mediamp.mpv.internal.headlessSurfaceBackend
import org.openani.mediamp.mpv.utils.MpvTestMedia
import org.openani.mediamp.playUri
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A file opened before the render context exists plays with `vo=null` and gets its video
 * output when the context appears (open-ani/mediamp#78). Windows and Linux are in that state
 * until the first surface attaches; [LateRenderContextPlayer] reproduces it on every platform
 * with a windowless render backend by withholding the context until
 * [LateRenderContextPlayer.attach].
 */
class MpvDeferredVideoOutputTest {

    @OptIn(InternalMediampApi::class)
    private class LateRenderContextPlayer(
        parentCoroutineContext: CoroutineContext,
        mainDispatcher: CoroutineDispatcher,
    ) : JvmMpvMediampPlayer(Any(), parentCoroutineContext, mainDispatcher) {
        val backend: MpvSurfaceBackend = checkNotNull(headlessSurfaceBackend())

        @Volatile
        private var renderContextCreated = false

        override fun ensureRenderContextForLoad(): Boolean = renderContextCreated

        /** Stands in for the first surface attach. */
        fun attach() {
            check(backend.createRenderContext(handle.ptr)) { "createRenderContext failed" }
            renderContextCreated = true
            renderContextBecameReady()
        }

        fun publishedFrameSerial(): Int? {
            val state = backend.getFrameState(handle.ptr)
            if (((state ushr 44) and 0xF).toInt() == 0xF) return null
            return (state and 0xFFFF).toInt()
        }
    }

    /**
     * An A/V clip whose only keyframe is its first frame, so an attach during playback always
     * lands mid-GOP. H.264 so the platform hardware decoder takes it; `null` when this ffmpeg
     * has no H.264 encoder (e.g. the LGPL runtime build), and the MPEG-4 fixture is used instead.
     */
    private fun generateSingleGopH264Clip(): File? {
        val tmp = System.getProperty("java.io.tmpdir")
        val target = File(tmp, "mediamp-mpv-test-h264-single-gop.mp4")
        if (target.isFile && target.length() > 0) return target
        val ffmpeg = MpvTestMedia.findFfmpeg() ?: return null
        for (encoder in listOf("libx264", "h264_videotoolbox", "h264_mf")) {
            val process = ProcessBuilder(
                ffmpeg, "-y",
                "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "8", "-c:v", encoder, "-g", "1000", "-sc_threshold", "0",
                "-pix_fmt", "yuv420p", "-c:a", "aac",
                target.absolutePath,
            ).redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            if (process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) return target
        }
        target.delete()
        return null
    }

    private suspend fun awaitProperty(handle: MPVHandle, name: String, expected: String) {
        withTimeout(10_000) {
            while (handle.getPropertyString(name) != expected) delay(20)
        }
    }

    @OptIn(InternalMediampApi::class)
    @Test
    fun `open before the render context exists plays and gains video on attach`() {
        if (!MpvTestMedia.prepareOrSkip(TAG)) return
        val clip = generateSingleGopH264Clip()
            ?: MpvTestMedia.generateClip(seconds = 8)
            ?: run {
                MpvTestMedia.skip(TAG, "ffmpeg unavailable or clip generation failed")
                return
            }

        // The machine checks command threads by physical identity: own exactly one thread.
        val mainDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "mpv-test-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        mainDispatcher.use {
            runBlocking(mainDispatcher) {
                val player = LateRenderContextPlayer(coroutineContext, mainDispatcher)
                val handle = player.handle
                try {
                    // A headless audio device may accept samples without consuming them,
                    // wedging the clock (see MpvHeadlessEofTest).
                    handle.setPropertyString("ao", "null")

                    withTimeout(15_000) { player.playUri(clip.absolutePath) }
                    assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
                    assertEquals("null", handle.getPropertyString("vo"))
                    withTimeout(10_000) { player.currentPositionMillis.first { it > 500 } }
                    // vo=libmpv without a render context would have deselected the track.
                    assertNotEquals(
                        "no",
                        handle.getPropertyString("vid"),
                        "the video track must stay selected",
                    )
                    assertEquals(null, player.publishedFrameSerial())
                    val hwdecBeforeAttach = handle.getPropertyString("hwdec-current")

                    player.attach()
                    awaitProperty(handle, "current-vo", "libmpv")
                    assertEquals("libmpv", handle.getPropertyString("vo"))

                    check(player.backend.setSurfaceConfig(handle.ptr, 320, 180, 0L)) {
                        "setSurfaceConfig failed"
                    }
                    val firstSerial = withTimeout(10_000) {
                        var serial = player.publishedFrameSerial()
                        while (serial == null) {
                            delay(20)
                            serial = player.publishedFrameSerial()
                        }
                        serial
                    }
                    // Frames keep coming and the clock keeps advancing after the switch.
                    withTimeout(10_000) {
                        while (player.publishedFrameSerial() == firstSerial) delay(20)
                    }
                    val position = player.currentPositionMillis.value
                    withTimeout(10_000) {
                        player.currentPositionMillis.first { it > position + 500 }
                    }
                    assertTrue(
                        player.state.value.isPlaying,
                        "playback must continue after the vo switch",
                    )
                    // A decoder restarted mid-GOP fails in hardware, and mpv then decodes the
                    // rest of the file in software.
                    if (hwdecBeforeAttach != null && hwdecBeforeAttach != "no") {
                        assertNotEquals(
                            "no",
                            handle.getPropertyString("hwdec-current"),
                            "the attach must keep hardware decoding (was $hwdecBeforeAttach)",
                        )
                    }

                    val dims = IntArray(2)
                    val pixels = checkNotNull(player.backend.readSurfacePixels(handle.ptr, dims)) {
                        "no frame to read back"
                    }
                    assertEquals(listOf(320, 180), dims.toList())
                    assertTrue(
                        pixels.count { (it and 0xFFFFFF) != 0 } > pixels.size / 2,
                        "the rendered frame must show the clip, not a blank surface",
                    )
                } finally {
                    player.backend.setSurfaceConfig(handle.ptr, 0, 0, 0L)
                    player.backend.destroyRenderContext(handle.ptr)
                    player.close()
                }
            }
        }
    }

    private companion object {
        const val TAG = "DeferredVideoOutputTest"
    }
}
