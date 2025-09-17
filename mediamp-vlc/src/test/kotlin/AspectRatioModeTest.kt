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
import org.openani.mediamp.features.AspectRatioMode
import kotlin.test.Test
import kotlin.test.assertEquals

class AspectRatioModeTest {
    private val calculator = FrameSizeCalculator()

    @Test
    fun `test FIT mode maintains aspect ratio and fits within container`() {
        // 16:9 video in 4:3 container
        calculator.calculate(
            imageSize = IntSize(1920, 1080),
            frameSize = Size(800f, 600f),
            aspectRatioMode = AspectRatioMode.FIT
        )

        // Should maintain 16:9 aspect ratio, fit height
        assertEquals(IntSize(800, 450), calculator.dstSize)
        assertEquals(IntOffset(0, 75), calculator.dstOffset) // Centered vertically
    }

    @Test
    fun `test STRETCH mode fills entire container`() {
        calculator.calculate(
            imageSize = IntSize(1920, 1080),
            frameSize = Size(800f, 600f),
            aspectRatioMode = AspectRatioMode.STRETCH
        )

        // Should fill entire container
        assertEquals(IntSize(800, 600), calculator.dstSize)
        assertEquals(IntOffset.Zero, calculator.dstOffset)
    }

    @Test
    fun `test FILL mode maintains aspect ratio and fills container`() {
        // 16:9 video in 4:3 container
        calculator.calculate(
            imageSize = IntSize(1920, 1080),
            frameSize = Size(800f, 600f),
            aspectRatioMode = AspectRatioMode.FILL
        )

        // Should maintain 16:9 aspect ratio, fill width
        assertEquals(IntSize(1067, 600), calculator.dstSize)
        assertEquals(IntOffset(-133, 0), calculator.dstOffset) // Centered horizontally
    }

    @Test
    fun `test caching works correctly with different modes`() {
        val imageSize = IntSize(1920, 1080)
        val frameSize = Size(800f, 600f)

        // First calculation with FIT
        calculator.calculate(imageSize, frameSize, AspectRatioMode.FIT)
        val fitSize = calculator.dstSize
        val fitOffset = calculator.dstOffset

        // Second calculation with same parameters should use cache
        calculator.calculate(imageSize, frameSize, AspectRatioMode.FIT)
        assertEquals(fitSize, calculator.dstSize)
        assertEquals(fitOffset, calculator.dstOffset)

        // Different mode should recalculate
        calculator.calculate(imageSize, frameSize, AspectRatioMode.STRETCH)
        assertEquals(IntSize(800, 600), calculator.dstSize)
        assertEquals(IntOffset.Zero, calculator.dstOffset)
    }
}