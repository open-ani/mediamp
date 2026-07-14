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
import org.openani.mediamp.mpv.internal.MpvSurfaceRing
import org.openani.mediamp.mpv.internal.MpvSurfaceRingBackend
import org.openani.mediamp.mpv.internal.OpenGLSurfaceRingBackend
import org.openani.mediamp.mpv.utils.OpenGLRenderEnvironment
import org.openani.mediamp.mpv.internal.currentSurfaceRingBackend
import kotlin.coroutines.CoroutineContext

@OptIn(InternalMediampApi::class)
actual class MpvMediampPlayer(
    context: Any,
    parentCoroutineContext: CoroutineContext,
) : JvmMpvMediampPlayer(context, parentCoroutineContext) {

    // Native surface-ring render path; the consumer state machine is shared (MpvSurfaceRing).
    private val ringBackend: MpvSurfaceRingBackend? = currentSurfaceRingBackend()
    private val surfaceRing: MpvSurfaceRing? = ringBackend?.let { MpvSurfaceRing(handle.ptr, it) }
    private var attachedOpenGLEnvironment: OpenGLRenderEnvironment? = null

    init {
        if (hostOs == OS.Linux) {
            // Prefer the two Linux paths mediamp validates: NVIDIA can keep decoded
            // frames on-GPU through CUDA/OpenGL, while Intel/AMD use stable VAAPI
            // decode with a system-memory copy. Only then try mpv's safe auto list.
            check(handle.setPropertyString("hwdec", "nvdec,vaapi-copy,auto-safe"))
        }
        // macOS and Windows own their producer device. Linux must first attach the live
        // Skiko GLX environment, otherwise `vo=libmpv` would be asked to load before its
        // required render context can exist.
        if (hostOs != OS.Linux) createRenderContext()
    }

    /** Creates the native render context and starts the render thread. Idempotent. */
    internal fun createRenderContext(): Boolean =
        ringBackend?.createRenderContext(handle.ptr) ?: false

    internal fun releaseRenderContext(): Boolean =
        ringBackend?.destroyRenderContext(handle.ptr) ?: false

    /**
     * Attaches Skiko's current GLX share environment. A new identity is a device
     * recreation: native code must discard its old producer context/share group before
     * the next `createRenderContext`, and the ring recreates its consumer wrappers on
     * the next published generation.
     */
    internal fun attachOpenGLRenderEnvironment(environment: OpenGLRenderEnvironment): Boolean {
        if (hostOs != OS.Linux) return false
        if (attachedOpenGLEnvironment?.identity == environment.identity) {
            return createRenderContext().also { if (it) renderContextBecameReady() }
        }
        if (attachedOpenGLEnvironment != null) {
            // mpv's render.h states that freeing mpv_render_context while video is active
            // forcibly disables video. Replacing Skiko's GLX share group therefore cannot
            // be handled by merely destroying and recreating context B: recovery would
            // require a separately verified video-output reload that preserves position,
            // pause state, selected track, and hwdec state.
            check(!hasActivePlaybackSession()) {
                "Skiko replaced its GLX share context during active playback. " +
                    "Transparent mpv video-output recovery is not supported."
            }
            // The native OpenGL renderer owns its GLX context and must release it before
            // accepting a new Skiko share context. The old GLX context owns its stale
            // FBOs; closing Skia wrappers below makes their texture references disappear.
            surfaceRing?.invalidateForRenderEnvironmentChange()
            releaseRenderContext()
        }
        val attached = (ringBackend as OpenGLSurfaceRingBackend)
            .attachRenderEnvironment(handle.ptr, environment)
        if (attached) {
            attachedOpenGLEnvironment = environment
            if (createRenderContext()) renderContextBecameReady()
        }
        return attached
    }

    override fun ensureRenderContextForLoad(): Boolean = when (hostOs) {
        OS.Linux -> attachedOpenGLEnvironment != null && createRenderContext()
        // Eager platforms keep the established load behavior even when a headless CI
        // environment cannot create an accelerated producer context.
        else -> true
    }

    internal fun currentOpenGLRenderEnvironment(): OpenGLRenderEnvironment? = attachedOpenGLEnvironment

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

    /** See [MpvSurfaceRingBackend.readSurfacePixels]. */
    internal fun readSurfacePixels(dims: IntArray): IntArray? =
        ringBackend?.readSurfacePixels(handle.ptr, dims)

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
