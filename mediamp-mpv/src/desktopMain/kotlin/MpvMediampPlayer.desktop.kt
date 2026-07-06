/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
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
        // This also starts the native render thread that drives all frame rendering.
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

    // macOS Metal path: the native render thread renders mpv into a triple-buffered
    // IOSurface ring; this side wraps the ring's MTLTextures as Skia surfaces and
    // samples whichever buffer the packed frame state marks as latest. All methods
    // below are called from the Compose/Skiko UI thread only.
    private val macosWrappedSurfaces = arrayOfNulls<Surface>(MACOS_BUFFER_COUNT)
    private val macosWrappedTargets = arrayOfNulls<BackendRenderTarget>(MACOS_BUFFER_COUNT)
    private var macosBlitSurface: Surface? = null
    private var macosWrappedGeneration = -1
    private var macosSurfaceContext: DirectContext? = null

    // Frame cache: blit + snapshot happen only when the native frame state advanced;
    // overlay-driven Compose redraws just re-draw the cached image.
    private var macosCachedFrame: Image? = null
    private var macosCachedState = 0L

    private var macosRequestedWidth = 0
    private var macosRequestedHeight = 0
    private var macosRequestedDevicePtr = 0L

    internal fun createMacosRenderContext(): Boolean = nCreateRenderContextMacos(handle.ptr)

    internal fun releaseMacosRenderContext(): Boolean = nDestroyRenderContextMacos(handle.ptr)

    /**
     * Asks the native render thread to size the buffer ring to [width] x [height]
     * (physical pixels, normally the composable size — mpv then scales, letterboxes and
     * renders subtitles at display resolution). Asynchronous and cheap: the swap
     * happens between frames on the render thread; until the first frame lands in the
     * new ring, [currentFrameImage] keeps returning the previous frame.
     */
    internal fun requestMacosSurface(width: Int, height: Int, mtlDevicePtr: Long): Boolean {
        if (width == macosRequestedWidth && height == macosRequestedHeight &&
            mtlDevicePtr == macosRequestedDevicePtr
        ) return true
        if (!nSetSurfaceConfigMacos(handle.ptr, width, height, mtlDevicePtr)) return false
        macosRequestedWidth = width
        macosRequestedHeight = height
        macosRequestedDevicePtr = mtlDevicePtr
        return true
    }

    /** Re-posts the current config when Skiko recreated its redrawer on a new MTLDevice. */
    internal fun refreshMacosDeviceIfChanged(mtlDevicePtr: Long) {
        if (macosRequestedWidth > 0 && mtlDevicePtr != macosRequestedDevicePtr) {
            requestMacosSurface(macosRequestedWidth, macosRequestedHeight, mtlDevicePtr)
        }
    }

    /**
     * Returns the latest video frame as a Skia-owned texture image (safe to draw
     * through Compose, including RenderNode recordings). The expensive part (blit +
     * snapshot) only runs when the native render thread published a new frame; other
     * redraws reuse the cached image. During a buffer-ring swap the previous frame is
     * returned until the new ring has content, so resizes never flash black. Do NOT
     * close the returned image — it is owned by the player.
     */
    internal fun currentFrameImage(directContext: DirectContext): Image? {
        val state = nGetFrameStateMacos(handle.ptr)
        if (state == macosCachedState && macosSurfaceContext === directContext) {
            macosCachedFrame?.let { return it }
        }
        val generation = ((state ushr 48) and 0xFFFF).toInt()
        val index = ((state ushr 44) and 0xF).toInt()
        val width = ((state ushr 30) and 0x3FFF).toInt()
        val height = ((state ushr 16) and 0x3FFF).toInt()
        if (index == 0xF || width <= 0 || height <= 0) return macosCachedFrame

        if (generation != macosWrappedGeneration ||
            macosSurfaceContext !== directContext ||
            macosBlitSurface == null
        ) {
            if (!rewrapMacosBuffers(generation, width, height, directContext)) {
                return macosCachedFrame
            }
        }

        val source = macosWrappedSurfaces[index] ?: return macosCachedFrame
        val blit = macosBlitSurface ?: return macosCachedFrame
        // The wrapped surface content was produced externally (GL); without this, Skia
        // reuses a generation-cached snapshot of the texture and the video freezes on
        // the first frame when sampled into another surface.
        source.notifyContentWillChange(ContentChangeMode.DISCARD)
        // Snapshots of a BRT-wrapped surface do not render (Skia does not own the
        // texture), so blit into a Skia-owned surface and snapshot that instead.
        source.draw(blit.canvas, 0, 0, null)
        macosCachedFrame?.close()
        macosCachedFrame = blit.makeImageSnapshot()
        macosCachedState = state
        return macosCachedFrame
    }

    private fun rewrapMacosBuffers(
        generation: Int,
        width: Int,
        height: Int,
        directContext: DirectContext,
    ): Boolean {
        if (macosSurfaceContext !== directContext) {
            // Images from a dead/replaced DirectContext must not be drawn again.
            macosCachedFrame?.close()
            macosCachedFrame = null
            macosCachedState = 0L
        }
        closeMacosWraps()
        // Our references to the previous generation are gone; the render thread may
        // free those buffers now.
        nAckRetiredBuffersMacos(handle.ptr)

        for (i in 0 until MACOS_BUFFER_COUNT) {
            val texture = nGetBufferTextureMacos(handle.ptr, i)
            if (texture == 0L) {
                closeMacosWraps()
                return false
            }
            val target = BackendRenderTarget.makeMetal(width, height, texture)
            val surface = runCatching {
                Surface.makeFromBackendRenderTarget(
                    context = directContext,
                    rt = target,
                    origin = SurfaceOrigin.TOP_LEFT,
                    colorFormat = SurfaceColorFormat.BGRA_8888,
                    colorSpace = ColorSpace.sRGB,
                )
            }.getOrNull()
            if (surface == null) {
                target.close()
                closeMacosWraps()
                return false
            }
            macosWrappedTargets[i] = target
            macosWrappedSurfaces[i] = surface
        }
        macosBlitSurface = runCatching {
            Surface.makeRenderTarget(directContext, false, ImageInfo.makeN32Premul(width, height))
        }.getOrNull()
        if (macosBlitSurface == null) {
            closeMacosWraps()
            return false
        }

        // The ack above may have unblocked a pending reconfig; if the ring was swapped
        // again while we were wrapping, these wraps are stale — drop them and let the
        // next draw retry against the newer generation.
        val currentGeneration = ((nGetFrameStateMacos(handle.ptr) ushr 48) and 0xFFFF).toInt()
        if (currentGeneration != generation) {
            closeMacosWraps()
            return false
        }

        macosWrappedGeneration = generation
        macosSurfaceContext = directContext
        return true
    }

    private fun closeMacosWraps() {
        for (i in 0 until MACOS_BUFFER_COUNT) {
            macosWrappedSurfaces[i]?.close()
            macosWrappedSurfaces[i] = null
            macosWrappedTargets[i]?.close()
            macosWrappedTargets[i] = null
        }
        macosBlitSurface?.close()
        macosBlitSurface = null
        macosWrappedGeneration = -1
    }

    internal fun releaseMacosSurface() {
        macosCachedFrame?.close()
        macosCachedFrame = null
        macosCachedState = 0L
        closeMacosWraps()
        macosSurfaceContext = null
        // All texture references are dropped, so the render thread may free both
        // generations immediately; frames go back to being drained.
        nSetSurfaceConfigMacos(handle.ptr, 0, 0, 0L)
        macosRequestedWidth = 0
        macosRequestedHeight = 0
        macosRequestedDevicePtr = 0L
    }

    internal fun dumpSurfaceForDebug(path: String): Boolean = nSaveSurfacePng(handle.ptr, path)

    /**
     * macOS: reads the frame back from our own IOSurface (mpv's screenshot pipeline
     * cannot convert hwdec videotoolbox frames without zimg). When no surface is
     * attached (headless capture), configures an ephemeral video-sized ring and waits
     * for the render thread to produce a frame in it.
     */
    override suspend fun takeScreenshotImpl(path: String): Boolean {
        if (hostOs == OS.MacOS) {
            val hadSurface = nHasMetalSurface(handle.ptr)
            var configured = false
            if (!hadSurface) {
                val width = handle.getPropertyInt("width")
                val height = handle.getPropertyInt("height")
                if (width <= 0 || height <= 0) return super.takeScreenshotImpl(path)
                configured = nSetSurfaceConfigMacos(handle.ptr, width, height, 0L)
                if (!configured) return super.takeScreenshotImpl(path)
                val rendered = withTimeoutOrNull(2_000) {
                    while (((nGetFrameStateMacos(handle.ptr) ushr 44) and 0xF).toInt() == 0xF) {
                        delay(10)
                    }
                    true
                } ?: false
                if (!rendered) {
                    nSetSurfaceConfigMacos(handle.ptr, 0, 0, 0L)
                    return super.takeScreenshotImpl(path)
                }
            }
            val saved = nSaveSurfacePng(handle.ptr, path)
            if (configured) nSetSurfaceConfigMacos(handle.ptr, 0, 0, 0L)
            if (saved) return true
        }
        return super.takeScreenshotImpl(path)
    }

    companion object {
        internal const val GL_TEXTURE_2D = 0x0DE1
        internal const val GL_RGBA8 = 0x8058
        internal const val MACOS_BUFFER_COUNT = 3

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
