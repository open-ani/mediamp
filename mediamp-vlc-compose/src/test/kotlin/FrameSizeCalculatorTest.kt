/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.vlc.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


private const val RATIO_EPS = 0.005f // 1%
private const val SIZE_EPS = 1        // pixel

class FrameSizeCalculatorTest {

    private fun aspectRatio(size: IntSize): Float =
        size.width.toFloat() / size.height.toFloat()

    // ---------- 1.   Landscape source, wide frame ----------
    @Test
    fun keepsAspect_landscapeImage_wideFrame() {
        val c = FrameSizeCalculator()
        val img = IntSize(1920, 1080)          // 16:9
        val frame = Size(800f, 600f)            // 4:3 – width-limited

        c.calculate(img, frame)

        // fits in frame
        assertTrue(c.dstSize.width <= frame.width.roundToInt() + SIZE_EPS)
        assertTrue(c.dstSize.height <= frame.height.roundToInt() + SIZE_EPS)

        // ratio preserved
        val delta = abs(aspectRatio(c.dstSize) - aspectRatio(img))
        assertTrue(delta < RATIO_EPS)

        // centered
        assertEquals(
            ((frame.width - c.dstSize.width) / 2f).roundToInt(),
            c.dstOffset.x,
        )
        assertEquals(
            ((frame.height - c.dstSize.height) / 2f).roundToInt(),
            c.dstOffset.y,
        )
    }

    // ---------- 2.   Portrait source, tall frame ----------
    @Test
    fun keepsAspect_portraitImage_tallFrame() {
        val c = FrameSizeCalculator()
        val img = IntSize(1080, 1920)          // 9:16
        val frame = Size(400f, 900f)            // height-limited

        c.calculate(img, frame)

        assertTrue(c.dstSize.width <= frame.width.roundToInt() + SIZE_EPS)
        assertTrue(c.dstSize.height <= frame.height.roundToInt() + SIZE_EPS)

        val delta = abs(aspectRatio(c.dstSize) - aspectRatio(img))
        assertTrue(delta < RATIO_EPS)
    }

    // ---------- 3.   Fractional frame pixels (dp-to-px rounding) ----------
    @Test
    fun fractionalFramePixels_doNotOverflow() {
        val c = FrameSizeCalculator()
        val img = IntSize(1600, 900)          // 16:9
        val frame = Size(300.4f, 200.6f)       // deliberate fractions

        c.calculate(img, frame)

        assertTrue(c.dstSize.width <= frame.width.roundToInt() + SIZE_EPS)
        assertTrue(c.dstSize.height <= frame.height.roundToInt() + SIZE_EPS)

        val delta = abs(aspectRatio(c.dstSize) - aspectRatio(img))
        assertTrue(delta < RATIO_EPS, "delta=$delta")
    }

    // ---------- 4.   Extreme down-scaling (1×1 px frame) ----------
    @Test
    fun extremeDownScale_doesNotCrash() {
        val c = FrameSizeCalculator()
        val img = IntSize(8000, 6000)
        val frame = Size(1f, 1f)

        c.calculate(img, frame)

        assertEquals(IntSize(1, 1), c.dstSize)
        assertEquals(IntOffset(0, 0), c.dstOffset)
    }

    // ---------- 5.   Cache sanity: same input → no change ----------
    @Test
    fun cachePreventsRecomputation() {
        val c = FrameSizeCalculator()
        val img = IntSize(1920, 1080)
        val frame = Size(500f, 500f)

        c.calculate(img, frame)
        val firstSize = c.dstSize
        val firstOffset = c.dstOffset

        // Call again with identical params
        c.calculate(img, frame)

        assertEquals(firstSize, c.dstSize)
        assertEquals(firstOffset, c.dstOffset)
    }
}
