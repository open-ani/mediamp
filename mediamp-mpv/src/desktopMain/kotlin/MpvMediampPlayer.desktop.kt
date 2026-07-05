/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs
import org.openani.mediamp.InternalMediampApi
import kotlin.coroutines.CoroutineContext

@OptIn(InternalMediampApi::class)
actual class MpvMediampPlayer(
    context: Any,
    parentCoroutineContext: CoroutineContext,
) : JvmMpvMediampPlayer(context, parentCoroutineContext) {
    init {
        // With vo=libmpv, mpv refuses to start the video track when no render context
        // exists at loadfile time ("no audio or video data played"). The macOS render
        // context owns its own offscreen CGL context and does not depend on any window,
        // so create it eagerly to remove the ordering dependency on the Compose surface.
        if (hostOs == OS.MacOS) {
            createMacosRenderContext()
        }
    }

    // Windows GL path: Skia objects wrapping the GL texture mpv renders into.
    internal var backendTexture: BackendTexture? = null
    internal var image: Image? = null

    fun releaseSkiaTextureAndImage() {
        image?.close()
        image = null
        backendTexture?.close()
        backendTexture = null
    }

    // macOS Metal path: mpv renders into an IOSurface which Skia samples as MTLTexture.
    internal var macosSkiaSurface: Surface? = null
        private set
    private var macosRenderTarget: BackendRenderTarget? = null
    private var macosSurfaceWidth = 0
    private var macosSurfaceHeight = 0

    internal fun createMacosRenderContext(): Boolean = nCreateRenderContextMacos(handle.ptr)

    internal fun releaseMacosRenderContext(): Boolean = nDestroyRenderContextMacos(handle.ptr)

    /** (Re)creates the IOSurface/FBO/MTLTexture chain when the composable size changes. */
    internal fun ensureMacosSurface(
        width: Int,
        height: Int,
        mtlDevicePtr: Long,
        directContext: DirectContext,
    ): Boolean {
        if (width == macosSurfaceWidth && height == macosSurfaceHeight && macosSkiaSurface != null) return true

        releaseMacosSurface()
        val metalTexture = nCreateMetalSurface(handle.ptr, width, height, mtlDevicePtr)
        if (metalTexture == 0L) return false

        val renderTarget = BackendRenderTarget.makeMetal(width, height, metalTexture)
        val surface = runCatching {
            Surface.makeFromBackendRenderTarget(
                context = directContext,
                rt = renderTarget,
                origin = SurfaceOrigin.TOP_LEFT,
                colorFormat = SurfaceColorFormat.BGRA_8888,
                colorSpace = ColorSpace.sRGB,
            )
        }.getOrNull()
        if (surface == null) {
            renderTarget.close()
            nReleaseMetalSurface(handle.ptr)
            return false
        }

        macosRenderTarget = renderTarget
        macosSkiaSurface = surface
        macosSurfaceWidth = width
        macosSurfaceHeight = height
        return true
    }

    internal fun renderFrameMacos(): Boolean = nRenderFrameMacos(handle.ptr)

    internal fun releaseMacosSurface() {
        macosSkiaSurface?.close()
        macosSkiaSurface = null
        macosRenderTarget?.close()
        macosRenderTarget = null
        macosSurfaceWidth = 0
        macosSurfaceHeight = 0
        nReleaseMetalSurface(handle.ptr)
    }

    companion object {
        internal const val GL_TEXTURE_2D = 0x0DE1
        internal const val GL_RGBA8 = 0x8058

        /**
         * Configures where the mpv native runtime (libmpv + JNI wrapper) is loaded from.
         * Must be called before the first [MpvMediampPlayer] is created.
         *
         * @param extractRuntimeLibrary extract the runtime bundled on the classpath into [path].
         * Pass `false` if [path] already contains the native libraries (e.g. a local dev build).
         */
        fun prepareLibraries(path: String, extractRuntimeLibrary: Boolean = true) {
            MPVHandle.setRuntimeLibraryDirectory(path, extractRuntimeLibrary)
        }

        fun prepareLibraries() {
            MPVHandle.useDefaultRuntimeLibraryDirectory()
        }
    }
}

actual fun limitDemuxer(): Boolean = false
