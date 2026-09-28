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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.internal.D3D11ReadbackSurfaceBackend
import org.openani.mediamp.mpv.internal.MpvSurfaceConsumer
import org.openani.mediamp.playUri
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The D3D11 readback path used for Skiko's software and ANGLE redrawers, end to end
 * without a window: native CPU readback -> [MpvSurfaceConsumer] -> raster image, with
 * no DirectContext involved.
 */
class D3D11ReadbackTest {

    private fun devNativeDir(): File? =
        System.getProperty("mediamp.mpv.dev.native.dir")
            ?.let(::File)
            ?.takeIf { it.resolve("mediampv.dll").isFile }

    private fun skip(reason: String): Boolean {
        System.err.println("[D3D11ReadbackTest] skipped: $reason")
        check(System.getProperty("mediamp.mpv.test.required") != "true") {
            "mpv headless tests are required on this runner but would be skipped: $reason"
        }
        return false
    }

    private fun prepareOrSkip(): Boolean {
        val osName = System.getProperty("os.name")
        if (!osName.contains("Windows")) {
            // Not applicable here, as opposed to a missing environment: never subject to
            // mediamp.mpv.test.required.
            System.err.println("[D3D11ReadbackTest] not applicable: D3D11 is Windows-only ($osName)")
            return false
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

    /** Polls like the Compose draw loop would until a frame of exactly [width] x [height] arrives. */
    private suspend fun awaitFrame(consumer: MpvSurfaceConsumer, width: Int, height: Int): Image? =
        withTimeoutOrNull(10_000) {
            var frame = consumer.currentFrameImage(directContext = null)
            while (frame == null || frame.width != width || frame.height != height) {
                delay(20)
                frame = consumer.currentFrameImage(directContext = null)
            }
            frame
        }

    @OptIn(InternalMediampApi::class)
    @Test
    fun `readback frames arrive as raster images and follow back-to-back resizes`() {
        if (!prepareOrSkip()) return
        val mainDispatcher = Dispatchers.Default.limitedParallelism(1)
        runBlocking(mainDispatcher) {
            val player = MpvMediampPlayer(Any(), coroutineContext, mainDispatcher = mainDispatcher)
            // The readback backend shares the headless D3D11 producer context.
            val consumer = D3D11ReadbackSurfaceBackend.createSurfaceConsumer(player.handle.ptr)
            try {
                check(player.createRenderContext()) { "createRenderContext failed" }
                (player.impl as MPVHandle).setPropertyString("ao", "null")
                player.playUri("av://lavfi:testsrc2=size=320x180:rate=30")

                // Several sizes in a row: a readback consumer never acks ring retirement,
                // so every reconfiguration must go through without one.
                for ((width, height) in listOf(320 to 180, 256 to 144, 160 to 90)) {
                    assertTrue(consumer.requestSurface(width, height, 0L), "requestSurface ${width}x$height")
                    val frame = assertNotNull(awaitFrame(consumer, width, height), "no ${width}x$height frame")
                    assertEquals(width, frame.width)
                    assertEquals(height, frame.height)

                    // testsrc2's leftmost bar is red; the rows are top-down RGBA.
                    val bitmap = Bitmap.makeFromImage(frame)
                    try {
                        val column = width / 20
                        val reds = (0 until height).count { y ->
                            val color = bitmap.getColor(column, y)
                            val r = (color ushr 16) and 0xFF
                            val g = (color ushr 8) and 0xFF
                            val b = color and 0xFF
                            r > 150 && g < 100 && b < 100
                        }
                        assertTrue(reds > height / 2, "expected the red bar at x=$column, got $reds/$height red pixels")
                    } finally {
                        bitmap.close()
                    }
                }
            } finally {
                consumer.release()
                player.releaseRenderContext()
                player.close()
            }
        }
    }
}
