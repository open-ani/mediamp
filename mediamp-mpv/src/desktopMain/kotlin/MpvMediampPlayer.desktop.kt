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
    private var macosBlitSurface: Surface? = null
    private var macosSurfaceWidth = 0
    private var macosSurfaceHeight = 0
    private var macosSurfaceContext: DirectContext? = null

    internal fun createMacosRenderContext(): Boolean = nCreateRenderContextMacos(handle.ptr)

    internal fun releaseMacosRenderContext(): Boolean = nDestroyRenderContextMacos(handle.ptr)

    /** (Re)creates the IOSurface/FBO/MTLTexture chain when the composable size changes. */
    internal fun ensureMacosSurface(
        width: Int,
        height: Int,
        mtlDevicePtr: Long,
        directContext: DirectContext,
    ): Boolean {
        // Recreate when the size changes OR Skiko swapped its DirectContext (redrawer
        // recreation): a surface built on a stale context silently renders nothing.
        if (width == macosSurfaceWidth && height == macosSurfaceHeight &&
            macosSkiaSurface != null && macosSurfaceContext === directContext
        ) return true

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

        // Snapshots of a BRT-wrapped surface do not render (Skia does not own the
        // texture); blit each frame into a Skia-owned surface and snapshot that instead.
        val blitSurface = runCatching {
            Surface.makeRenderTarget(
                directContext, false,
                org.jetbrains.skia.ImageInfo.makeN32Premul(width, height),
            )
        }.getOrNull()
        if (blitSurface == null) {
            surface.close()
            renderTarget.close()
            nReleaseMetalSurface(handle.ptr)
            return false
        }

        macosRenderTarget = renderTarget
        macosSkiaSurface = surface
        macosBlitSurface = blitSurface
        macosSurfaceWidth = width
        macosSurfaceHeight = height
        macosSurfaceContext = directContext
        return true
    }

    internal fun renderFrameMacos(): Boolean = nRenderFrameMacos(handle.ptr)

    /**
     * Renders the freshest mpv frame and returns it as a Skia-owned texture image
     * (safe to draw through Compose, including RenderNode recordings). Caller closes.
     */
    internal fun makeFrameImage(): org.jetbrains.skia.Image? {
        val source = macosSkiaSurface ?: return null
        val blit = macosBlitSurface ?: return null
        nRenderFrameMacos(handle.ptr)
        // The wrapped surface was just modified externally (GL); without this, Skia
        // reuses a generation-cached snapshot of the texture and the video freezes on
        // the first (black) frame when sampled into another surface.
        source.notifyContentWillChange(org.jetbrains.skia.ContentChangeMode.DISCARD)
        source.draw(blit.canvas, 0, 0, null)
        return blit.makeImageSnapshot()
    }

    internal fun dumpSurfaceForDebug(path: String): Boolean = nSaveSurfacePng(handle.ptr, path)

    /**
     * macOS: reads the frame back from our own IOSurface (mpv's screenshot pipeline
     * cannot convert hwdec videotoolbox frames without zimg). Creates an ephemeral
     * video-sized surface when none is attached (headless capture).
     */
    override suspend fun takeScreenshotImpl(path: String): Boolean {
        if (hostOs == OS.MacOS) {
            val hadSurface = nHasMetalSurface(handle.ptr)
            if (!hadSurface) {
                val width = handle.getPropertyInt("width")
                val height = handle.getPropertyInt("height")
                if (width <= 0 || height <= 0) return super.takeScreenshotImpl(path)
                if (nCreateMetalSurface(handle.ptr, width, height, 0L) == 0L) {
                    return super.takeScreenshotImpl(path)
                }
            }
            nRenderFrameMacos(handle.ptr)
            val saved = nSaveSurfacePng(handle.ptr, path)
            if (!hadSurface) nReleaseMetalSurface(handle.ptr)
            if (saved) return true
        }
        return super.takeScreenshotImpl(path)
    }

    internal fun releaseMacosSurface() {
        macosSkiaSurface?.close()
        macosSkiaSurface = null
        macosBlitSurface?.close()
        macosBlitSurface = null
        macosRenderTarget?.close()
        macosRenderTarget = null
        macosSurfaceWidth = 0
        macosSurfaceHeight = 0
        macosSurfaceContext = null
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
