/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.backend.vlc

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

@Composable
fun MediaPlayerSurfaceWithVlc(
    mediampPlayer: VlcMediampPlayer,
    modifier: Modifier = Modifier,
) {
    val frameSizeCalculator = remember {
        FrameSizeCalculator()
    }
    Canvas(modifier) {
        val bitmap = mediampPlayer.surface.bitmap ?: return@Canvas
        frameSizeCalculator.calculate(
            IntSize(bitmap.width, bitmap.height),
            Size(size.width, size.height),
        )
        drawImage(
            bitmap,
            dstSize = frameSizeCalculator.dstSize,
            dstOffset = frameSizeCalculator.dstOffset,
            filterQuality = FilterQuality.High,
        )
    }
}


private class FrameSizeCalculator {
    private var lastImageSize: IntSize = IntSize.Zero
    private var lastFrameSize: Size = Size.Zero

    // no boxing
    var dstSize: IntSize = IntSize.Zero
    var dstOffset: IntOffset = IntOffset.Zero

    private fun calculateImageSizeAndOffsetToFillFrame(
        imageWidth: Int,
        imageHeight: Int,
        frameWidth: Float,
        frameHeight: Float
    ) {
        // 计算图片和画框的宽高比
        val imageAspectRatio = imageWidth.toFloat() / imageHeight.toFloat()

        // 初始化最终的宽度和高度
        val finalWidth = frameWidth
        val finalHeight = frameWidth / imageAspectRatio
        if (finalHeight > frameHeight) {
            // 如果高度超出了画框的高度，那么就使用高度来计算宽度
            val finalHeight2 = frameHeight
            val finalWidth2 = frameHeight * imageAspectRatio
            dstSize = IntSize(finalWidth2.roundToInt(), finalHeight2.roundToInt())
            dstOffset = IntOffset(((frameWidth - finalWidth2) / 2).roundToInt(), 0)
            return
        }

        // 计算左上角的偏移量
        val offsetX = 0
        val offsetY = (frameHeight - finalHeight) / 2

        dstSize = IntSize(finalWidth.roundToInt(), finalHeight.roundToInt())
        dstOffset = IntOffset(offsetX, offsetY.roundToInt())
    }

    fun calculate(
        imageSize: IntSize,
        frameSize: Size,
    ) {
        // 缓存上次计算结果, 因为这个函数会每帧绘制都调用
        if (lastImageSize == imageSize && lastFrameSize == frameSize) {
            return
        }
        calculateImageSizeAndOffsetToFillFrame(
            imageWidth = imageSize.width, imageHeight = imageSize.height,
            frameWidth = frameSize.width, frameHeight = frameSize.height,
        )
        lastImageSize = imageSize
        lastFrameSize = frameSize
    }
}
