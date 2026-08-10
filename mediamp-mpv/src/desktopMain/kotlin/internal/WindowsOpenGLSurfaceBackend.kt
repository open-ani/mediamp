/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import org.jetbrains.skiko.SkiaLayer
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.nCopyLatestFrameWindowsOpenGL
import org.openani.mediamp.mpv.nCreateRenderContextWindowsOpenGL
import org.openani.mediamp.mpv.nDestroyRenderContextWindowsOpenGL
import org.openani.mediamp.mpv.nGetFrameStateWindowsOpenGL
import org.openani.mediamp.mpv.nHasWindowsOpenGLSurface
import org.openani.mediamp.mpv.nReadSurfacePixelsWindowsOpenGL
import org.openani.mediamp.mpv.nSaveSurfacePngWindowsOpenGL
import org.openani.mediamp.mpv.nSetSurfaceConfigWindowsOpenGL
import org.openani.mediamp.mpv.utils.SkiaRenderDeviceInterop
import org.openani.mediamp.mpv.utils.SkiaWindowsOpenGLInterop

/**
 * Windows OpenGL fallback backend (render_opengl_win.cpp): drives mpv when Compose
 * renders with Skiko's OpenGL backend (`SKIKO_RENDER_API=OPENGL`) instead of the
 * Direct3D default, where the D3D11 shared-texture path has no D3D12 consumer device.
 *
 * The producer is a private offscreen WGL context on a dedicated render thread; it
 * shares nothing with Skiko — frames leave it as CPU pixel copies which the consumer
 * ([MpvReadbackSurface]) uploads into a Skia surface during draw. One GPU->CPU->GPU
 * round trip per frame by design: this is the compatibility path, chosen for
 * robustness over zero-copy (no wglShareLists, no pixel-format matching against
 * Skiko's drawable). Because the producer needs nothing from the live Skiko renderer,
 * the eager lifecycle applies, and the frame-preview decoder can create its own
 * context freely.
 */
@OptIn(InternalMediampApi::class)
internal object WindowsOpenGLSurfaceBackend : MpvSurfaceBackend {
    override fun createRenderContext(ptr: Long) = nCreateRenderContextWindowsOpenGL(ptr)
    override fun destroyRenderContext(ptr: Long) = nDestroyRenderContextWindowsOpenGL(ptr)

    // No consumer device: frames leave the producer through a CPU copy.
    override fun setSurfaceConfig(ptr: Long, width: Int, height: Int, devicePtr: Long) =
        nSetSurfaceConfigWindowsOpenGL(ptr, width, height)

    override fun getFrameState(ptr: Long) = nGetFrameStateWindowsOpenGL(ptr)
    override fun hasSurface(ptr: Long) = nHasWindowsOpenGLSurface(ptr)
    override fun saveSurfacePng(ptr: Long, path: String) = nSaveSurfacePngWindowsOpenGL(ptr, path)
    override fun readSurfacePixels(ptr: Long, dims: IntArray) = nReadSurfacePixelsWindowsOpenGL(ptr, dims)

    /** See [nCopyLatestFrameWindowsOpenGL]. */
    fun copyLatestFrame(ptr: Long, destAddr: Long, width: Int, height: Int): Long =
        nCopyLatestFrameWindowsOpenGL(ptr, destAddr, width, height)

    override fun createSurfaceConsumer(handlePtr: Long): MpvSurfaceConsumer =
        MpvReadbackSurface(handlePtr, this)

    override val rendererName: String get() = "OpenGL/WGL readback"
    override fun createSkiaInterop(layer: SkiaLayer): SkiaRenderDeviceInterop =
        SkiaWindowsOpenGLInterop(layer)

    override fun createRenderContextLifecycle(host: MpvRenderContextHost): MpvRenderContextLifecycle =
        WindowsOpenGLRenderContextLifecycle(EagerRenderContextLifecycle(this, host), host)
}

/**
 * Eager lifecycle with the fallback's decode constraint: a GL renderer has no zero-copy
 * D3D11/DXVA2 interop in this build, so decode on the GPU through D3D11 and copy the
 * frames back to system memory for mpv's GL upload; mpv's safe auto list covers codecs
 * d3d11va cannot handle. Deliberately not plain `auto`, which prefers direct-mapping
 * modes whose interop does not exist here.
 */
internal class WindowsOpenGLRenderContextLifecycle(
    private val eager: EagerRenderContextLifecycle,
    private val host: MpvRenderContextHost,
) : MpvRenderContextLifecycle by eager {
    override fun initialize() {
        check(host.handle.setPropertyString("hwdec", "d3d11va-copy,auto-safe"))
        eager.initialize()
    }
}
