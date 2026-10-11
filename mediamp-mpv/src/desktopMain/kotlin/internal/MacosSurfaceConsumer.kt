/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.MPVLog
import org.openani.mediamp.mpv.nCopyLatestFrameMacos
import org.openani.mediamp.mpv.nCreateRenderContextMacos
import org.openani.mediamp.mpv.nDestroyRenderContextMacos
import org.openani.mediamp.mpv.nGetFrameStateMacos
import org.openani.mediamp.mpv.nHasMetalSurface
import org.openani.mediamp.mpv.nReadSurfacePixelsMacos
import org.openani.mediamp.mpv.nSaveSurfacePngMacos
import org.openani.mediamp.mpv.nSetReadbackSurfaceConfigMacos
import org.openani.mediamp.mpv.utils.SkiaLayerRedrawer
import org.openani.mediamp.mpv.utils.SkiaReadbackInterop
import org.openani.mediamp.mpv.utils.SkiaRenderDeviceInterop

/**
 * The CPU readback mode of the macOS producer (render_macos.mm): the same render context
 * as [MacosSurfaceRingBackend], with every frame also copied out of its IOSurface. Never
 * attached as a player backend; [MacosSurfaceConsumer] switches to it.
 */
@OptIn(InternalMediampApi::class)
internal object MacosReadbackSurfaceBackend : MpvReadbackBackend {
    override fun createRenderContext(ptr: Long) = nCreateRenderContextMacos(ptr)
    override fun destroyRenderContext(ptr: Long) = nDestroyRenderContextMacos(ptr)

    // No consumer device: frames leave the producer through a CPU copy.
    override fun setSurfaceConfig(ptr: Long, width: Int, height: Int, devicePtr: Long) =
        nSetReadbackSurfaceConfigMacos(ptr, width, height)

    override fun getFrameState(ptr: Long) = nGetFrameStateMacos(ptr)
    override fun hasSurface(ptr: Long) = nHasMetalSurface(ptr)
    override fun saveSurfacePng(ptr: Long, path: String) = nSaveSurfacePngMacos(ptr, path)
    override fun readSurfacePixels(ptr: Long, dims: IntArray) = nReadSurfacePixelsMacos(ptr, dims)

    override fun copyLatestFrame(ptr: Long, destAddr: Long, width: Int, height: Int): Long =
        nCopyLatestFrameMacos(ptr, destAddr, width, height)

    override val rendererName: String get() = "Metal readback"
    override fun createSkiaInterop(layerRedrawer: SkiaLayerRedrawer): SkiaRenderDeviceInterop = SkiaReadbackInterop
}

/**
 * Consumer of [MacosSurfaceRingBackend]. Skiko draws macOS windows with Metal unless it
 * fell back to a software redrawer (no usable Metal device), possibly only at runtime after
 * a failed frame. With a Metal device the IOSurface ring is sampled zero-copy
 * ([MpvSurfaceRing]); without one (`SkiaMetalInterop` reports device 0, as headless
 * surfaces pass) the producer switches to CPU readback and frames are drawn as raster
 * images ([MpvReadbackSurface]). The surface composable re-reads the device on every draw
 * ([refreshDeviceIfChanged]), so a runtime fallback switches over as well. The producer and
 * its render context stay the same either way, which is why this is one backend whose
 * consumer adapts rather than two backends: the player creates the macOS render context
 * before any window exists, so loading never waits for a surface.
 *
 * All methods are called from the Compose/Skiko UI thread only.
 */
internal class MacosSurfaceConsumer(private val handlePtr: Long) : MpvSurfaceConsumer {
    private val ring = MpvSurfaceRing(handlePtr, MacosSurfaceRingBackend)
    private val readback = MpvReadbackSurface(handlePtr, MacosReadbackSurfaceBackend)
    private var active: MpvSurfaceConsumer? = null

    private var requestedWidth = 0
    private var requestedHeight = 0

    private fun consumerFor(devicePtr: Long): MpvSurfaceConsumer = if (devicePtr == 0L) readback else ring

    override fun requestSurface(width: Int, height: Int, devicePtr: Long): Boolean {
        val target = consumerFor(devicePtr)
        if (target !== active) {
            // The outgoing mode drops its Skia objects first; the producer then frees its
            // ring when it applies the new config (see surface_ring.h).
            active?.release()
            active = target
            if (target === readback) {
                MPVLog.log(handlePtr, MPVLog.INFO, "no Metal device to share frames with; drawing CPU readback frames")
            }
        }
        if (!target.requestSurface(width, height, devicePtr)) return false
        requestedWidth = width
        requestedHeight = height
        return true
    }

    override fun refreshDeviceIfChanged(devicePtr: Long) {
        val current = active ?: return
        if (consumerFor(devicePtr) === current) {
            current.refreshDeviceIfChanged(devicePtr)
        } else if (requestedWidth > 0) {
            requestSurface(requestedWidth, requestedHeight, devicePtr)
        }
    }

    override fun invalidateForRenderEnvironmentChange() {
        active?.invalidateForRenderEnvironmentChange()
    }

    override fun currentFrameImage(directContext: DirectContext?): Image? =
        active?.currentFrameImage(directContext)

    override fun release() {
        active?.release()
        active = null
        requestedWidth = 0
        requestedHeight = 0
    }
}
