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
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.internal.D3D11SurfaceRingBackend
import org.openani.mediamp.mpv.internal.MacosSurfaceRingBackend
import org.openani.mediamp.mpv.internal.MpvSurfaceRing
import org.openani.mediamp.mpv.internal.MpvSurfaceRingBackend
import kotlin.coroutines.CoroutineContext

@OptIn(InternalMediampApi::class)
actual class MpvMediampPlayer(
    context: Any,
    parentCoroutineContext: CoroutineContext,
) : JvmMpvMediampPlayer(context, parentCoroutineContext) {

    // Native surface-ring render path: macOS renders through Metal/IOSurface
    // (render_macos.mm), Windows through D3D11/D3D12 shared textures
    // (render_d3d11.cpp). The consumer state machine is shared (MpvSurfaceRing).
    private val ringBackend: MpvSurfaceRingBackend? = when (hostOs) {
        OS.MacOS -> MacosSurfaceRingBackend
        OS.Windows -> D3D11SurfaceRingBackend
        else -> null // TODO: Linux render path
    }
    private val surfaceRing: MpvSurfaceRing? = ringBackend?.let { MpvSurfaceRing(handle.ptr, it) }

    init {
        // With vo=libmpv, mpv refuses to start the video track when no render context
        // exists at loadfile time ("no audio or video data played"). Both desktop
        // render contexts own their device (offscreen CGL context on macOS, our own
        // ID3D11Device on Windows) and do not depend on any window, so create them
        // eagerly to remove the ordering dependency on the Compose surface. This also
        // starts the native render thread that drives all frame rendering.
        createRenderContext()
    }

    /** Creates the native render context and starts the render thread. Idempotent. */
    internal fun createRenderContext(): Boolean =
        ringBackend?.createRenderContext(handle.ptr) ?: false

    internal fun releaseRenderContext(): Boolean =
        ringBackend?.destroyRenderContext(handle.ptr) ?: false

    /** See [MpvSurfaceRing.requestSurface]. */
    internal fun requestSurface(width: Int, height: Int, devicePtr: Long): Boolean =
        surfaceRing?.requestSurface(width, height, devicePtr) ?: false

    /** See [MpvSurfaceRing.refreshDeviceIfChanged]. */
    internal fun refreshDeviceIfChanged(devicePtr: Long) {
        surfaceRing?.refreshDeviceIfChanged(devicePtr)
    }

    /** See [MpvSurfaceRing.currentFrameImage]. Do NOT close the returned image. */
    internal fun currentFrameImage(directContext: DirectContext): Image? =
        surfaceRing?.currentFrameImage(directContext)

    /** See [MpvSurfaceRing.release]. */
    internal fun releaseSurface() {
        surfaceRing?.release()
    }

    internal fun dumpSurfaceForDebug(path: String): Boolean =
        ringBackend?.saveSurfacePng(handle.ptr, path) ?: false

    /**
     * Reads the frame back from our own surface ring (mpv's screenshot pipeline cannot
     * convert hwdec videotoolbox/d3d11va frames without zimg). When no surface is
     * attached (headless capture), configures an ephemeral video-sized ring and waits
     * for the render thread to produce a frame in it.
     */
    override suspend fun takeScreenshotImpl(path: String): Boolean {
        val backend = ringBackend ?: return super.takeScreenshotImpl(path)
        val ptr = handle.ptr
        val hadSurface = backend.hasSurface(ptr)
        var configured = false
        if (!hadSurface) {
            val width = handle.getPropertyInt("width")
            val height = handle.getPropertyInt("height")
            if (width <= 0 || height <= 0) return super.takeScreenshotImpl(path)
            configured = backend.setSurfaceConfig(ptr, width, height, 0L)
            if (!configured) return super.takeScreenshotImpl(path)
            val rendered = withTimeoutOrNull(2_000) {
                while (((backend.getFrameState(ptr) ushr 44) and 0xF).toInt() == 0xF) {
                    delay(10)
                }
                true
            } ?: false
            if (!rendered) {
                backend.setSurfaceConfig(ptr, 0, 0, 0L)
                return super.takeScreenshotImpl(path)
            }
        }
        val saved = backend.saveSurfacePng(ptr, path)
        if (configured) backend.setSurfaceConfig(ptr, 0, 0, 0L)
        if (saved) return true
        return super.takeScreenshotImpl(path)
    }

    companion object {
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
