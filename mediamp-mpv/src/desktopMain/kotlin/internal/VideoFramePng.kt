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

/**
 * Encodes [pixels] (`0xAARRGGBB`, row-major, [width] per row) as an opaque PNG at [path].
 * Returns false when the PNG cannot be written.
 */
internal fun writeFramePng(pixels: IntArray, width: Int, height: Int, path: String): Boolean {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return false
    return try {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, width, height, pixels, 0, width)
        val file = File(path)
        file.absoluteFile.parentFile?.mkdirs()
        ImageIO.write(image, "png", file)
    } catch (e: Exception) {
        false
    }
}
