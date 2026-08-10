/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.Surface
import org.openani.mediamp.mpv.MPVLog

/**
 * Consumer side of the Windows OpenGL fallback: the native render thread publishes CPU
 * frames (RGBA_8888, top-down on copy); this class copies the latest one straight into
 * a Skia bitmap's native pixel storage (no JNI arrays, no Java-heap staging), uploads
 * it into a GPU surface on Skia's current DirectContext, and caches the snapshot. The
 * expensive part (copy + upload) only runs when the native frame state advanced;
 * overlay-driven Compose redraws just re-draw the cached image. During a resize the
 * previous frame is returned until a frame at the new size exists, so resizes never
 * flash black. All methods are called from the Compose/Skiko UI thread only.
 */
internal class MpvReadbackSurface(
    private val handlePtr: Long,
    private val backend: WindowsOpenGLSurfaceBackend,
) : MpvSurfaceConsumer {
    private var bitmap: Bitmap? = null
    private var bitmapPixels: Pixmap? = null
    private var bitmapWidth = 0
    private var bitmapHeight = 0

    private var blitSurface: Surface? = null
    private var surfaceContext: DirectContext? = null

    private var cachedFrame: Image? = null
    private var cachedState = 0L

    private var requestedWidth = 0
    private var requestedHeight = 0
    private var configured = false

    override fun requestSurface(width: Int, height: Int, devicePtr: Long): Boolean {
        if (configured && width == requestedWidth && height == requestedHeight) return true
        if (!backend.setSurfaceConfig(handlePtr, width, height, 0L)) return false
        requestedWidth = width
        requestedHeight = height
        configured = true
        return true
    }

    override fun refreshDeviceIfChanged(devicePtr: Long) {
        // Frames arrive as CPU pixels; no consumer render device is involved. A
        // replaced Skiko DirectContext is detected per draw in currentFrameImage.
    }

    override fun invalidateForRenderEnvironmentChange() {
        dropConsumerResources()
    }

    override fun currentFrameImage(directContext: DirectContext): Image? {
        val state = backend.getFrameState(handlePtr)
        if (state == cachedState && surfaceContext === directContext) {
            cachedFrame?.let { return it }
        }
        val index = ((state ushr 44) and 0xF).toInt()
        val width = ((state ushr 30) and 0x3FFF).toInt()
        val height = ((state ushr 16) and 0x3FFF).toInt()
        if (index == 0xF || width <= 0 || height <= 0) return cachedFrame

        if (surfaceContext !== directContext) {
            // Skiko replaced its redrawer: GPU objects of the old DirectContext are
            // dead and must not be drawn or closed into the new one late — drop them
            // now, before anything is created on the new context.
            cachedFrame?.close()
            cachedFrame = null
            cachedState = 0L
            blitSurface?.close()
            blitSurface = null
            surfaceContext = null
        }

        val destAddr = ensureBitmap(width, height) ?: return cachedFrame
        // Copy first: it can still fail benignly (the render thread swapped in a frame
        // of another size between the state read above and now), and then the cached
        // image must stay untouched.
        val copiedState = backend.copyLatestFrame(handlePtr, destAddr, width, height)
        if (copiedState == 0L) return cachedFrame
        val blit = ensureBlitSurface(width, height, directContext) ?: return cachedFrame

        // Closing the previous snapshot before writing lets Skia reuse the surface's
        // texture instead of copy-on-writing it; the image was drawn in an already
        // flushed Compose frame.
        cachedFrame?.close()
        cachedFrame = null
        blit.writePixels(bitmap!!, 0, 0)
        cachedFrame = blit.makeImageSnapshot()
        cachedState = copiedState
        return cachedFrame
    }

    /** The bitmap's native pixel address for [width] x [height], or null on failure. */
    private fun ensureBitmap(width: Int, height: Int): Long? {
        if (bitmap != null && bitmapWidth == width && bitmapHeight == height) {
            return bitmapPixels?.addr
        }
        bitmapPixels?.close()
        bitmapPixels = null
        bitmap?.close()
        bitmap = null
        val allocated = Bitmap()
        // The native copy forces sane alpha, but the video is opaque either way.
        if (!allocated.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE))) {
            allocated.close()
            logOnce("bitmap allocation failed (${width}x${height})", MPVLog.ERROR)
            return null
        }
        val pixels = allocated.peekPixels()
        if (pixels == null) {
            allocated.close()
            logOnce("bitmap pixels are not peekable; frames stay native-only", MPVLog.ERROR)
            return null
        }
        bitmap = allocated
        bitmapPixels = pixels
        bitmapWidth = width
        bitmapHeight = height
        return pixels.addr
    }

    private fun ensureBlitSurface(width: Int, height: Int, directContext: DirectContext): Surface? {
        blitSurface?.let {
            if (surfaceContext === directContext && it.width == width && it.height == height) return it
            it.close()
            blitSurface = null
        }
        // A GPU (render-target) surface: writePixels is the one CPU->GPU upload, and
        // its snapshot is drawn zero-copy onto the Compose canvas. Same color type as
        // the bitmap, so the upload is a straight copy — an N32 (BGRA) surface would
        // make writePixels swizzle the whole frame on the CPU first.
        blitSurface = runCatching {
            Surface.makeRenderTarget(
                directContext, false,
                ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
            )
        }.onFailure { logOnce("blit surface creation failed", MPVLog.ERROR, it) }.getOrNull()
        if (blitSurface == null) {
            logOnce("blit surface unavailable (${width}x${height})", MPVLog.ERROR)
            return null
        }
        surfaceContext = directContext
        return blitSurface
    }

    private fun dropConsumerResources() {
        cachedFrame?.close()
        cachedFrame = null
        cachedState = 0L
        blitSurface?.close()
        blitSurface = null
        surfaceContext = null
        bitmapPixels?.close()
        bitmapPixels = null
        bitmap?.close()
        bitmap = null
        bitmapWidth = 0
        bitmapHeight = 0
    }

    private val loggedStates = mutableSetOf<String>()
    private fun logOnce(message: String, level: Int = MPVLog.WARN, throwable: Throwable? = null) {
        if (loggedStates.add(message)) MPVLog.log(handlePtr, level, message, throwable)
    }

    override fun release() {
        dropConsumerResources()
        // The render thread may drop its target and go back to draining frames.
        backend.setSurfaceConfig(handlePtr, 0, 0, 0L)
        requestedWidth = 0
        requestedHeight = 0
        configured = false
    }
}
