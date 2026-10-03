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
import kotlin.test.assertTrue

class VideoFramePngTest {
    @Test
    fun `png holds the frame top-down with opaque pixels`() {
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        // 3x2 frame: red row above green row
        val pixels = intArrayOf(red, red, red, green, green, green)
        val file = File.createTempFile("mediamp-frame", ".png")
        try {
            assertTrue(writeFramePng(pixels, 3, 2, file.absolutePath))
            val image = assertNotNull(ImageIO.read(file))
            assertEquals(3, image.width)
            assertEquals(2, image.height)
            assertEquals(0xFF0000, image.getRGB(1, 0) and 0xFFFFFF)
            assertEquals(0x00FF00, image.getRGB(1, 1) and 0xFFFFFF)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `invalid sizes are rejected`() {
        val file = File.createTempFile("mediamp-bad", ".png")
        try {
            assertFalse(writeFramePng(IntArray(1), 2, 2, file.absolutePath))
            assertFalse(writeFramePng(IntArray(4), 0, 2, file.absolutePath))
        } finally {
            file.delete()
        }
    }
}
