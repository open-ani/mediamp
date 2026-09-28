/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.nCopyLatestFrameD3D11
import org.openani.mediamp.mpv.nCreateRenderContextD3D11
import org.openani.mediamp.mpv.nDestroyRenderContextD3D11
import org.openani.mediamp.mpv.nGetFrameStateD3D11
import org.openani.mediamp.mpv.nHasD3D11Surface
import org.openani.mediamp.mpv.nReadSurfacePixelsD3D11
import org.openani.mediamp.mpv.nSaveSurfacePngD3D11
import org.openani.mediamp.mpv.nSetReadbackSurfaceConfigD3D11
import org.openani.mediamp.mpv.utils.SkiaLayerRedrawer
import org.openani.mediamp.mpv.utils.SkiaReadbackInterop
import org.openani.mediamp.mpv.utils.SkiaRenderDeviceInterop

/**
 * Windows backend for Skiko redrawers that have no D3D12 or OpenGL device to share
 * with: the software redrawers (Skiko's last fallback, e.g. when Direct3D 12 is
 * unavailable on Windows ARM64, over Remote Desktop, or in VMs) and ANGLE.
 *
 * The producer is the regular D3D11 render path (render_d3d11.cpp) on the default
 * adapter, which falls back to WARP, so it works wherever Direct3D 11 does, unlike the
 * WGL producer, which needs a real OpenGL driver. The render thread copies each frame to
 * system memory and [MpvReadbackSurface] draws it as a raster image, so nothing from
 * Skiko's renderer is needed and no Skiko internals are reflected.
 */
@OptIn(InternalMediampApi::class)
internal object D3D11ReadbackSurfaceBackend : MpvReadbackBackend {
    override fun createRenderContext(ptr: Long) = nCreateRenderContextD3D11(ptr)
    override fun destroyRenderContext(ptr: Long) = nDestroyRenderContextD3D11(ptr)

    // No consumer device: frames leave the producer through a CPU copy.
    override fun setSurfaceConfig(ptr: Long, width: Int, height: Int, devicePtr: Long) =
        nSetReadbackSurfaceConfigD3D11(ptr, width, height)

    override fun getFrameState(ptr: Long) = nGetFrameStateD3D11(ptr)
    override fun hasSurface(ptr: Long) = nHasD3D11Surface(ptr)
    override fun saveSurfacePng(ptr: Long, path: String) = nSaveSurfacePngD3D11(ptr, path)
    override fun readSurfacePixels(ptr: Long, dims: IntArray) = nReadSurfacePixelsD3D11(ptr, dims)

    override fun copyLatestFrame(ptr: Long, destAddr: Long, width: Int, height: Int): Long =
        nCopyLatestFrameD3D11(ptr, destAddr, width, height)

    override val rendererName: String get() = "D3D11 readback"
    override fun createSkiaInterop(layerRedrawer: SkiaLayerRedrawer): SkiaRenderDeviceInterop = SkiaReadbackInterop
}
