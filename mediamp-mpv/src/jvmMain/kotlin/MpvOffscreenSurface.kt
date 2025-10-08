/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps

internal class MpvOffscreenSurface {
    private var surface: Surface? = null
    private var renderTarget: BackendRenderTarget? = null
    private var context: DirectContext? = null
    private var texturePtr: Long = 0
    private var width = 0
    private var height = 0

    val fboId: Int
        get() = OffscreenGL.getFboId(texturePtr)

    fun ensure(width: Int, height: Int) {
        if (surface != null && this.width == width && this.height == height) return
        dispose()
        texturePtr = OffscreenGL.createTextureFbo(width, height)
        val fbo = OffscreenGL.getFboId(texturePtr)
        context = DirectContext.makeGL()
        renderTarget = BackendRenderTarget.makeGL(width, height, 0, 8, fbo, FramebufferFormat.GR_GL_RGBA8)
        surface = Surface.makeFromBackendRenderTarget(
            context!!,
            renderTarget!!,
            SurfaceOrigin.BOTTOM_LEFT,
            SurfaceColorFormat.RGBA_8888,
            ColorSpace.sRGB,
            SurfaceProps(pixelGeometry = PixelGeometry.UNKNOWN),
        )
        this.width = width
        this.height = height
    }

    fun canvas(): Canvas? = surface?.canvas

    fun drawTo(dest: Canvas) {
        surface?.flushAndSubmit()
        val img = surface!!.makeImageSnapshot()
        dest.drawImageRect(img, Rect.makeWH(width.toFloat(), height.toFloat()))
        img.close()
    }

    fun dispose() {
        surface?.close(); surface = null
        renderTarget?.close(); renderTarget = null
        context?.close(); context = null
        if (texturePtr != 0L) {
            OffscreenGL.disposeTextureFbo(texturePtr)
            texturePtr = 0
        }
    }
}
