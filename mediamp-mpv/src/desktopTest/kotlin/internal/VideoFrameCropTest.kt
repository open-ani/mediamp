/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoFrameCropTest {
    private fun rect(frameWidth: Int, frameHeight: Int, osd: OsdDimensions): FrameRect =
        assertNotNull(videoRectInFrame(frameWidth, frameHeight, osd))

    private fun assertRect(expected: FrameRect, actual: FrameRect) {
        assertEquals(
            listOf(expected.x, expected.y, expected.width, expected.height),
            listOf(actual.x, actual.y, actual.width, actual.height),
        )
    }

    @Test
    fun `no margins keeps the full frame`() {
        assertNull(videoRectInFrame(1920, 1080, OsdDimensions(1920, 1080, 0, 0, 0, 0)))
    }

    @Test
    fun `pillarbox margins are removed`() {
        // 4:3 video inside a 16:9 target
        val osd = OsdDimensions(1920, 1080, marginLeft = 240, marginTop = 0, marginRight = 240, marginBottom = 0)
        assertRect(FrameRect(240, 0, 1440, 1080), rect(1920, 1080, osd))
    }

    @Test
    fun `letterbox margins are removed`() {
        // 2.35:1 video inside a 16:9 target
        val osd = OsdDimensions(1920, 1080, marginLeft = 0, marginTop = 131, marginRight = 0, marginBottom = 131)
        assertRect(FrameRect(0, 131, 1920, 818), rect(1920, 1080, osd))
    }

    @Test
    fun `margins scale to a frame of another size`() {
        val osd = OsdDimensions(1920, 1080, marginLeft = 240, marginTop = 0, marginRight = 240, marginBottom = 0)
        assertRect(FrameRect(120, 0, 720, 540), rect(960, 540, osd))
    }

    @Test
    fun `inconsistent margins are ignored`() {
        assertNull(videoRectInFrame(1920, 1080, OsdDimensions(1920, 1080, -1, 0, 0, 0)))
        assertNull(videoRectInFrame(1920, 1080, OsdDimensions(1920, 1080, 960, 0, 960, 0)))
        assertNull(videoRectInFrame(1920, 1080, OsdDimensions(0, 0, 10, 10, 10, 10)))
        assertNull(videoRectInFrame(0, 0, OsdDimensions(1920, 1080, 10, 10, 10, 10)))
    }

    @Test
    fun `png keeps only the cropped pixels`() {
        val black = 0xFF000000.toInt()
        val red = 0xFFFF0000.toInt()
        // 4x2 frame: black columns at both ends, red in the middle
        val pixels = intArrayOf(
            black, red, red, black,
            black, red, red, black,
        )
        val file = File.createTempFile("mediamp-crop", ".png")
        try {
            assertTrue(writeFramePng(pixels, 4, 2, FrameRect(1, 0, 2, 2), file.absolutePath))
            val image = assertNotNull(ImageIO.read(file))
            assertEquals(2, image.width)
            assertEquals(2, image.height)
            for (x in 0 until 2) for (y in 0 until 2) {
                assertEquals(0xFF0000, image.getRGB(x, y) and 0xFFFFFF, "pixel ($x, $y)")
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `png of the full frame without a rect`() {
        val pixels = IntArray(6) { 0xFF00FF00.toInt() }
        val file = File.createTempFile("mediamp-full", ".png")
        try {
            assertTrue(writeFramePng(pixels, 3, 2, null, file.absolutePath))
            val image = assertNotNull(ImageIO.read(file))
            assertEquals(3, image.width)
            assertEquals(2, image.height)
            assertEquals(0x00FF00, image.getRGB(2, 1) and 0xFFFFFF)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `rect outside the frame is rejected`() {
        val bad = File.createTempFile("mediamp-bad", ".png")
        try {
            assertFalse(writeFramePng(IntArray(4), 2, 2, FrameRect(1, 1, 2, 2), bad.absolutePath))
            assertFalse(writeFramePng(IntArray(1), 2, 2, null, bad.absolutePath))
        } finally {
            bad.delete()
        }
    }
}
