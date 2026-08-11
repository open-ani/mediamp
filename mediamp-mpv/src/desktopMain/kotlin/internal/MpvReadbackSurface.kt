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
import org.openani.mediamp.mpv.MPVLog

/**
 * Consumer side of the Windows OpenGL fallback: the native render thread publishes CPU
 * frames; this class copies the latest one into a fresh immutable Skia bitmap's native
 * pixel storage (no JNI arrays, no Java-heap staging) and wraps it zero-copy as a
 * raster [Image]. Drawing that image on the Compose canvas uploads it into Skia's
 * bitmap-texture cache once per unique frame; overlay-driven redraws without a new
 * frame re-draw the cached image and hit the same cached texture.
 *
 * Deliberately owns NO GPU objects. Raster images and bitmaps have CPU-only
 * destructors, so they can be closed regardless of which (if any) GL context is
 * current — the dispose path at window close runs without a current context, where
 * destroying a GPU surface blocks or crashes in the driver (observed: an EDT hang in
 * the surface destructor while the producer rendered, and a null-dispatch
 * ACCESS_VIOLATION from a DirectContext flush). The per-frame GPU upload cost is the
 * same as an explicit blit-surface design; only the upload's bookkeeping moved into
 * Skia's own cache, which handles context loss itself.
 *
 * All methods are called from the Compose/Skiko UI thread only.
 */
internal class MpvReadbackSurface(
    private val handlePtr: Long,
    private val backend: WindowsOpenGLSurfaceBackend,
) : MpvSurfaceConsumer {
    // The bitmap of the current frame stays referenced (and unclosed) while its image
    // is cached: the image shares the immutable bitmap's pixels instead of copying.
    private var bitmap: Bitmap? = null
    private var bitmapPixels: Pixmap? = null
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
        // Frames arrive as CPU pixels and leave as raster images; no consumer render
        // device is involved, and Skia's texture cache survives redrawer changes on
        // its own.
    }

    override fun invalidateForRenderEnvironmentChange() {
        dropConsumerResources()
    }

    override fun currentFrameImage(directContext: DirectContext): Image? {
        val state = backend.getFrameState(handlePtr)
        if (state == cachedState) {
            cachedFrame?.let { return it }
        }
        val index = ((state ushr 44) and 0xF).toInt()
        val width = ((state ushr 30) and 0x3FFF).toInt()
        val height = ((state ushr 16) and 0x3FFF).toInt()
        if (index == 0xF || width <= 0 || height <= 0) return cachedFrame

        // A fresh bitmap per frame: the previous one is pinned by the previous image
        // (shared pixels) until both are replaced below.
        val newBitmap = Bitmap()
        if (!newBitmap.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE))) {
            newBitmap.close()
            logOnce("bitmap allocation failed (${width}x${height})", MPVLog.ERROR)
            return cachedFrame
        }
        val newPixels = newBitmap.peekPixels()
        if (newPixels == null) {
            newBitmap.close()
            logOnce("bitmap pixels are not peekable; frames stay native-only", MPVLog.ERROR)
            return cachedFrame
        }
        // The copy can fail benignly (the render thread swapped in a frame of another
        // size between the state read above and now); the cached image stays untouched.
        val copiedState = backend.copyLatestFrame(handlePtr, newPixels.addr, width, height)
        if (copiedState == 0L) {
            newPixels.close()
            newBitmap.close()
            return cachedFrame
        }
        // Immutable, so makeFromBitmap shares the pixels instead of copying, and Skia
        // may cache the uploaded texture under the image's unique ID.
        newBitmap.setImmutable()
        val newFrame = Image.makeFromBitmap(newBitmap)

        // CPU-only destructors — safe on any thread with any (or no) GL context
        // current. Skia purges a cached texture of a closed image through its own
        // message bus, never from this destructor.
        cachedFrame?.close()
        bitmapPixels?.close()
        bitmap?.close()
        cachedFrame = newFrame
        bitmap = newBitmap
        bitmapPixels = newPixels
        cachedState = copiedState
        return newFrame
    }

    private fun dropConsumerResources() {
        cachedFrame?.close()
        cachedFrame = null
        cachedState = 0L
        bitmapPixels?.close()
        bitmapPixels = null
        bitmap?.close()
        bitmap = null
    }

    private val loggedStates = mutableSetOf<String>()
    private fun logOnce(message: String, level: Int = MPVLog.WARN, throwable: Throwable? = null) {
        if (loggedStates.add(message)) MPVLog.log(handlePtr, level, message, throwable)
    }

    override fun release() {
        // Deactivation is synchronous: after it returns, the render thread has dropped
        // its FBO and parked, so the native side can no longer write into the bitmap
        // being closed below (copies only ever run on this thread anyway; this also
        // quiesces mpv's frame flow before the player is torn down).
        backend.setSurfaceConfig(handlePtr, 0, 0, 0L)
        dropConsumerResources()
        requestedWidth = 0
        requestedHeight = 0
        configured = false
    }
}
