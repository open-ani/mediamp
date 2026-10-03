/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * mpv's `osd-dimensions` property: the render target size in OSD units and the letterbox
 * margins of the video inside it. With `keepaspect` mpv scales the video to fit and fills
 * the margins with black; `panscan=1` (crop) and `keepaspect=no` (stretch) report 0.
 */
internal class OsdDimensions(
    val width: Int,
    val height: Int,
    val marginLeft: Int,
    val marginTop: Int,
    val marginRight: Int,
    val marginBottom: Int,
)

/** A pixel rectangle inside a readback frame; [x] and [y] are its top-left corner. */
internal class FrameRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The rectangle the video occupies inside a readback frame of [frameWidth] x [frameHeight]
 * pixels, derived from [osd]. OSD units equal render-target pixels, so the margins apply
 * directly when the sizes match; a frame of another size (a ring generation rendered before
 * the latest resize) gets the margins scaled proportionally.
 *
 * Returns null when there is nothing to crop or the data is inconsistent (negative margins,
 * margins covering the whole frame); callers then keep the full frame.
 */
internal fun videoRectInFrame(frameWidth: Int, frameHeight: Int, osd: OsdDimensions): FrameRect? {
    if (frameWidth <= 0 || frameHeight <= 0 || osd.width <= 0 || osd.height <= 0) return null
    if (osd.marginLeft < 0 || osd.marginTop < 0 || osd.marginRight < 0 || osd.marginBottom < 0) return null
    if (osd.marginLeft == 0 && osd.marginTop == 0 && osd.marginRight == 0 && osd.marginBottom == 0) return null

    val scaleX = frameWidth.toDouble() / osd.width
    val scaleY = frameHeight.toDouble() / osd.height
    val left = (osd.marginLeft * scaleX).roundToInt()
    val top = (osd.marginTop * scaleY).roundToInt()
    val right = frameWidth - (osd.marginRight * scaleX).roundToInt()
    val bottom = frameHeight - (osd.marginBottom * scaleY).roundToInt()
    if (right - left < 1 || bottom - top < 1) return null
    return FrameRect(left, top, right - left, bottom - top)
}

/**
 * Encodes [pixels] (`0xAARRGGBB`, row-major, [frameWidth] per row) as an opaque PNG at
 * [path], keeping only [rect] when given. Returns false when the PNG cannot be written.
 */
internal fun writeFramePng(
    pixels: IntArray,
    frameWidth: Int,
    frameHeight: Int,
    rect: FrameRect?,
    path: String,
): Boolean {
    val crop = rect ?: FrameRect(0, 0, frameWidth, frameHeight)
    if (crop.width <= 0 || crop.height <= 0 || crop.x < 0 || crop.y < 0) return false
    if (crop.x + crop.width > frameWidth || crop.y + crop.height > frameHeight) return false
    if (pixels.size < frameWidth * frameHeight) return false
    return try {
        val image = BufferedImage(crop.width, crop.height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, crop.width, crop.height, pixels, crop.y * frameWidth + crop.x, frameWidth)
        val file = File(path)
        file.absoluteFile.parentFile?.mkdirs()
        ImageIO.write(image, "png", file)
    } catch (e: Exception) {
        false
    }
}
