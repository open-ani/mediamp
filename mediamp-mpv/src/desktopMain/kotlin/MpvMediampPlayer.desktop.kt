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

    // Frame cache: mpv render + blit + snapshot happen only when mpv actually produced
    // a new frame; overlay-driven Compose redraws just re-draw the cached image.
    private var macosCachedFrame: org.jetbrains.skia.Image? = null
    private var macosCachedTick = -1L
    // Resize debounce: overlay/page animations resize the composable every frame;
    // recreating the whole IOSurface chain each time visibly stutters playback. Keep
    // rendering at the old size (scaled at draw time) until the size settles.
    private var macosDesiredWidth = 0
    private var macosDesiredHeight = 0
    private var macosSizeChangedAtMs = 0L

    internal fun createMacosRenderContext(): Boolean = nCreateRenderContextMacos(handle.ptr)

    internal fun releaseMacosRenderContext(): Boolean = nDestroyRenderContextMacos(handle.ptr)

    /**
     * (Re)creates the IOSurface/FBO/MTLTexture chain when the composable size changes.
     * Size changes are debounced ([SIZE_SETTLE_MILLIS]): during layout animations the
     * chain keeps its old size and the cached frame is scaled at draw time.
     */
    internal fun ensureMacosSurface(
        width: Int,
        height: Int,
        mtlDevicePtr: Long,
        directContext: DirectContext,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (width != macosDesiredWidth || height != macosDesiredHeight) {
            macosDesiredWidth = width
            macosDesiredHeight = height
            macosSizeChangedAtMs = now
        }

        val contextValid = macosSkiaSurface != null && macosSurfaceContext === directContext
        if (contextValid && width == macosSurfaceWidth && height == macosSurfaceHeight) return true
        // Keep the old chain while the size is still animating; scale at draw time.
        if (contextValid && now - macosSizeChangedAtMs < SIZE_SETTLE_MILLIS) return true

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
     * Returns the current video frame as a Skia-owned texture image (safe to draw
     * through Compose, including RenderNode recordings). The expensive part (mpv
     * render + blit + snapshot) only runs when [frameTick] advanced, i.e. mpv actually
     * produced a new frame; overlay-driven redraws reuse the cached image. Do NOT close
     * the returned image — it is owned by the player.
     */
    internal fun currentFrameImage(frameTick: Long): org.jetbrains.skia.Image? {
        val source = macosSkiaSurface ?: return null
        val blit = macosBlitSurface ?: return null
        if (frameTick == macosCachedTick) {
            macosCachedFrame?.let { return it }
        }

        nRenderFrameMacos(handle.ptr)
        // The wrapped surface was just modified externally (GL); without this, Skia
        // reuses a generation-cached snapshot of the texture and the video freezes on
        // the first (black) frame when sampled into another surface.
        source.notifyContentWillChange(org.jetbrains.skia.ContentChangeMode.DISCARD)
        source.draw(blit.canvas, 0, 0, null)

        macosCachedFrame?.close()
        macosCachedFrame = blit.makeImageSnapshot()
        macosCachedTick = frameTick
        return macosCachedFrame
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
        macosCachedFrame?.close()
        macosCachedFrame = null
        macosCachedTick = -1L
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
        private const val SIZE_SETTLE_MILLIS = 150L

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
