/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.MPVLog
import org.openani.mediamp.mpv.nAckRetiredBuffersD3D11
import org.openani.mediamp.mpv.nAckRetiredBuffersMacos
import org.openani.mediamp.mpv.nCreateRenderContextD3D11
import org.openani.mediamp.mpv.nCreateRenderContextMacos
import org.openani.mediamp.mpv.nDestroyRenderContextD3D11
import org.openani.mediamp.mpv.nDestroyRenderContextMacos
import org.openani.mediamp.mpv.nGetBufferTextureD3D11
import org.openani.mediamp.mpv.nGetBufferTextureMacos
import org.openani.mediamp.mpv.nGetFrameStateD3D11
import org.openani.mediamp.mpv.nGetFrameStateMacos
import org.openani.mediamp.mpv.nHasD3D11Surface
import org.openani.mediamp.mpv.nHasMetalSurface
import org.openani.mediamp.mpv.nSaveSurfacePng
import org.openani.mediamp.mpv.nSaveSurfacePngD3D11
import org.openani.mediamp.mpv.nSetSurfaceConfigD3D11
import org.openani.mediamp.mpv.nSetSurfaceConfigMacos

internal const val SURFACE_RING_BUFFER_COUNT = 3

/**
 * Platform half of the native surface-ring render path: the JNI entry points plus how
 * a ring buffer's native texture is wrapped for Skia. The consumer state machine on top
 * ([MpvSurfaceRing]) is identical on macOS (Metal/IOSurface, render_macos.mm) and
 * Windows (D3D11/D3D12 shared textures, render_d3d11.cpp).
 */
internal interface MpvSurfaceRingBackend {
    fun createRenderContext(ptr: Long): Boolean
    fun destroyRenderContext(ptr: Long): Boolean

    /**
     * [devicePtr] is the consumer-side render device: an MTLDevice pointer on macOS, a
     * pointer to Skiko's native DirectXDevice struct on Windows; 0 requests a
     * consumer-less (headless) ring.
     */
    fun setSurfaceConfig(ptr: Long, width: Int, height: Int, devicePtr: Long): Boolean
    fun getFrameState(ptr: Long): Long
    fun getBufferTexture(ptr: Long, index: Int): Long
    fun ackRetiredBuffers(ptr: Long): Boolean
    fun hasSurface(ptr: Long): Boolean
    fun saveSurfacePng(ptr: Long, path: String): Boolean

    fun makeRenderTarget(width: Int, height: Int, texturePtr: Long): BackendRenderTarget
    val wrapColorFormat: SurfaceColorFormat
}

@OptIn(InternalMediampApi::class)
internal object MacosSurfaceRingBackend : MpvSurfaceRingBackend {
    override fun createRenderContext(ptr: Long) = nCreateRenderContextMacos(ptr)
    override fun destroyRenderContext(ptr: Long) = nDestroyRenderContextMacos(ptr)
    override fun setSurfaceConfig(ptr: Long, width: Int, height: Int, devicePtr: Long) =
        nSetSurfaceConfigMacos(ptr, width, height, devicePtr)

    override fun getFrameState(ptr: Long) = nGetFrameStateMacos(ptr)
    override fun getBufferTexture(ptr: Long, index: Int) = nGetBufferTextureMacos(ptr, index)
    override fun ackRetiredBuffers(ptr: Long) = nAckRetiredBuffersMacos(ptr)
    override fun hasSurface(ptr: Long) = nHasMetalSurface(ptr)
    override fun saveSurfacePng(ptr: Long, path: String) = nSaveSurfacePng(ptr, path)

    override fun makeRenderTarget(width: Int, height: Int, texturePtr: Long): BackendRenderTarget =
        BackendRenderTarget.makeMetal(width, height, texturePtr)

    override val wrapColorFormat: SurfaceColorFormat get() = SurfaceColorFormat.BGRA_8888
}

@OptIn(InternalMediampApi::class)
internal object D3D11SurfaceRingBackend : MpvSurfaceRingBackend {
    private const val DXGI_FORMAT_R8G8B8A8_UNORM = 28

    override fun createRenderContext(ptr: Long) = nCreateRenderContextD3D11(ptr)
    override fun destroyRenderContext(ptr: Long) = nDestroyRenderContextD3D11(ptr)
    override fun setSurfaceConfig(ptr: Long, width: Int, height: Int, devicePtr: Long) =
        nSetSurfaceConfigD3D11(ptr, width, height, devicePtr)

    override fun getFrameState(ptr: Long) = nGetFrameStateD3D11(ptr)
    override fun getBufferTexture(ptr: Long, index: Int) = nGetBufferTextureD3D11(ptr, index)
    override fun ackRetiredBuffers(ptr: Long) = nAckRetiredBuffersD3D11(ptr)
    override fun hasSurface(ptr: Long) = nHasD3D11Surface(ptr)
    override fun saveSurfacePng(ptr: Long, path: String) = nSaveSurfacePngD3D11(ptr, path)

    override fun makeRenderTarget(width: Int, height: Int, texturePtr: Long): BackendRenderTarget =
        BackendRenderTarget.makeDirect3D(
            width = width,
            height = height,
            texturePtr = texturePtr,
            format = DXGI_FORMAT_R8G8B8A8_UNORM,
            sampleCnt = 1,
            levelCnt = 1,
        )

    // RGB_888x would sidestep the alpha channel entirely, but Skia's D3D backend
    // refuses to wrap render targets with a non-renderable color type (returns null),
    // so wrap as RGBA_8888. mpv's d3d11 renderer writes alpha=1 for opaque video
    // (verified by the demo pixel readback), so premultiplied sampling is safe.
    override val wrapColorFormat: SurfaceColorFormat get() = SurfaceColorFormat.RGBA_8888
}

/**
 * Consumer side of the native surface-ring render path: the native render thread
 * renders mpv into a triple-buffered ring of GPU textures; this class wraps the ring's
 * textures as Skia surfaces and samples whichever buffer the packed frame state marks
 * as latest. All methods are called from the Compose/Skiko UI thread only.
 */
internal class MpvSurfaceRing(
    private val handlePtr: Long,
    private val backend: MpvSurfaceRingBackend,
) {
    private val wrappedSurfaces = arrayOfNulls<Surface>(SURFACE_RING_BUFFER_COUNT)
    private val wrappedTargets = arrayOfNulls<BackendRenderTarget>(SURFACE_RING_BUFFER_COUNT)
    private var blitSurface: Surface? = null
    private var wrappedGeneration = -1
    private var surfaceContext: DirectContext? = null

    // Frame cache: blit + snapshot happen only when the native frame state advanced;
    // overlay-driven Compose redraws just re-draw the cached image.
    private var cachedFrame: Image? = null
    private var cachedState = 0L

    private var requestedWidth = 0
    private var requestedHeight = 0
    private var requestedDevicePtr = 0L

    /**
     * Asks the native render thread to size the buffer ring to [width] x [height]
     * (physical pixels, normally the composable size — mpv then scales, letterboxes and
     * renders subtitles at display resolution). Asynchronous and cheap: the swap
     * happens between frames on the render thread; until the first frame lands in the
     * new ring, [currentFrameImage] keeps returning the previous frame.
     */
    fun requestSurface(width: Int, height: Int, devicePtr: Long): Boolean {
        if (width == requestedWidth && height == requestedHeight &&
            devicePtr == requestedDevicePtr
        ) return true
        if (!backend.setSurfaceConfig(handlePtr, width, height, devicePtr)) return false
        requestedWidth = width
        requestedHeight = height
        requestedDevicePtr = devicePtr
        return true
    }

    /** Re-posts the current config when Skiko recreated its redrawer on a new device. */
    fun refreshDeviceIfChanged(devicePtr: Long) {
        if (requestedWidth > 0 && devicePtr != requestedDevicePtr) {
            requestSurface(requestedWidth, requestedHeight, devicePtr)
        }
    }

    /**
     * Returns the latest video frame as a Skia-owned texture image (safe to draw
     * through Compose, including RenderNode recordings). The expensive part (blit +
     * snapshot) only runs when the native render thread published a new frame; other
     * redraws reuse the cached image. During a buffer-ring swap the previous frame is
     * returned until the new ring has content, so resizes never flash black. Do NOT
     * close the returned image — it is owned by this ring.
     */
    fun currentFrameImage(directContext: DirectContext): Image? {
        val state = backend.getFrameState(handlePtr)
        if (state == cachedState && surfaceContext === directContext) {
            cachedFrame?.let { return it }
        }
        val generation = ((state ushr 48) and 0xFFFF).toInt()
        val index = ((state ushr 44) and 0xF).toInt()
        val width = ((state ushr 30) and 0x3FFF).toInt()
        val height = ((state ushr 16) and 0x3FFF).toInt()
        if (index == 0xF || width <= 0 || height <= 0) return cachedFrame

        if (generation != wrappedGeneration ||
            surfaceContext !== directContext ||
            blitSurface == null
        ) {
            if (!rewrapBuffers(generation, width, height, directContext)) {
                return cachedFrame
            }
        }

        val source = wrappedSurfaces[index] ?: return cachedFrame
        val blit = blitSurface ?: return cachedFrame
        // The wrapped surface content was produced externally (GL/D3D11); without this,
        // Skia reuses a generation-cached snapshot of the texture and the video freezes
        // on the first frame when sampled into another surface.
        source.notifyContentWillChange(ContentChangeMode.DISCARD)
        // Snapshots of a BRT-wrapped surface do not render (Skia does not own the
        // texture), so blit into a Skia-owned GPU surface and snapshot that. The snapshot
        // is a GPU image on the current DirectContext; the caller draws it straight onto
        // the Compose canvas with nativeCanvas.drawImage (see MpvMediampPlayerSurface),
        // a zero-copy GPU->GPU draw. Do NOT convert it with toComposeImageBitmap(): that
        // reads the GPU image back to a CPU bitmap every frame, which both costs a full
        // GPU->CPU transfer (a ~20ms stall at 4K that pins the whole Compose scene, and
        // the danmaku overlay with it, to ~40fps) and crashes on window resize (the
        // readback runs inside reshape()'s redraw against a stale swapchain context).
        source.draw(blit.canvas, 0, 0, null)
        cachedFrame?.close()
        cachedFrame = blit.makeImageSnapshot()
        cachedState = state
        return cachedFrame
    }

    private fun rewrapBuffers(
        generation: Int,
        width: Int,
        height: Int,
        directContext: DirectContext,
    ): Boolean {
        if (surfaceContext !== directContext) {
            // Images from a dead/replaced DirectContext must not be drawn again.
            cachedFrame?.close()
            cachedFrame = null
            cachedState = 0L
        }
        closeWraps()
        // Our references to the previous generation are gone; the render thread may
        // free those buffers now.
        backend.ackRetiredBuffers(handlePtr)

        for (i in 0 until SURFACE_RING_BUFFER_COUNT) {
            val texture = backend.getBufferTexture(handlePtr, i)
            if (texture == 0L) {
                logOnce("buffer $i has no consumer-side texture; frames stay native-only")
                closeWraps()
                return false
            }
            val target = backend.makeRenderTarget(width, height, texture)
            val surface = runCatching {
                Surface.makeFromBackendRenderTarget(
                    context = directContext,
                    rt = target,
                    origin = SurfaceOrigin.TOP_LEFT,
                    colorFormat = backend.wrapColorFormat,
                    colorSpace = ColorSpace.sRGB,
                )
            }.onFailure { logOnce("wrapping buffer $i as Skia surface failed", MPVLog.ERROR, it) }.getOrNull()
            if (surface == null) {
                logOnce("Surface.makeFromBackendRenderTarget returned null (format=${backend.wrapColorFormat})", MPVLog.ERROR)
                target.close()
                closeWraps()
                return false
            }
            wrappedTargets[i] = target
            wrappedSurfaces[i] = surface
        }
        // A GPU (render-target) blit surface: the blit is GPU->GPU and its snapshot is
        // drawn zero-copy onto the Compose canvas, so no frame ever crosses the CPU.
        blitSurface = runCatching {
            Surface.makeRenderTarget(directContext, false, ImageInfo.makeN32Premul(width, height))
        }.onFailure { logOnce("blit surface creation failed", MPVLog.ERROR, it) }.getOrNull()
        if (blitSurface == null) {
            logOnce("blit surface unavailable (${width}x${height})", MPVLog.ERROR)
            closeWraps()
            return false
        }

        // The ack above may have unblocked a pending reconfig; if the ring was swapped
        // again while we were wrapping, these wraps are stale — drop them and let the
        // next draw retry against the newer generation.
        val currentGeneration = ((backend.getFrameState(handlePtr) ushr 48) and 0xFFFF).toInt()
        if (currentGeneration != generation) {
            closeWraps()
            return false
        }

        wrappedGeneration = generation
        surfaceContext = directContext
        return true
    }

    private fun closeWraps() {
        for (i in 0 until SURFACE_RING_BUFFER_COUNT) {
            wrappedSurfaces[i]?.close()
            wrappedSurfaces[i] = null
            wrappedTargets[i]?.close()
            wrappedTargets[i] = null
        }
        blitSurface?.close()
        blitSurface = null
        wrappedGeneration = -1
    }

    private val loggedStates = mutableSetOf<String>()
    private fun logOnce(message: String, level: Int = MPVLog.WARN, throwable: Throwable? = null) {
        if (loggedStates.add(message)) MPVLog.log(level, message, throwable)
    }

    fun release() {
        cachedFrame?.close()
        cachedFrame = null
        cachedState = 0L
        closeWraps()
        surfaceContext = null
        // All texture references are dropped, so the render thread may free both
        // generations immediately; frames go back to being drained.
        backend.setSurfaceConfig(handlePtr, 0, 0, 0L)
        requestedWidth = 0
        requestedHeight = 0
        requestedDevicePtr = 0L
    }
}
