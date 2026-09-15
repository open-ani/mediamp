/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.internal.MpvRenderContextHost
import org.openani.mediamp.mpv.internal.MpvRenderContextLifecycle
import org.openani.mediamp.mpv.internal.MpvSurfaceBackend
import org.openani.mediamp.mpv.internal.MpvSurfaceConsumer
import org.openani.mediamp.mpv.internal.currentSurfaceBackend
import org.openani.mediamp.mpv.internal.headlessSurfaceBackend
import org.openani.mediamp.mpv.internal.supportsSurfaceBackend
import org.openani.mediamp.mpv.utils.SkiaLayerRedrawer
import org.openani.mediamp.mpv.utils.SkiaRenderDeviceInterop
import kotlin.coroutines.CoroutineContext

@OptIn(InternalMediampApi::class)
actual class MpvMediampPlayer(
    context: Any,
    parentCoroutineContext: CoroutineContext,
    /**
     * The dispatcher the state machine is confined to (spec §4). Defaults to
     * [Dispatchers.Main], which on desktop JVM is the Swing EDT. The machine captures the
     * dispatcher's thread identity itself for the fail-fast command check.
     */
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : JvmMpvMediampPlayer(context, parentCoroutineContext, mainDispatcher) {

    // Windows cannot choose its producer until the window has a live Skiko redrawer.
    // Keep the backend, consumer, and lifecycle together so frame previews use the same path.
    private class Rendering(
        val backend: MpvSurfaceBackend,
        val surface: MpvSurfaceConsumer,
        val lifecycle: MpvRenderContextLifecycle,
    )

    @Volatile
    private var rendering: Rendering? = null
    internal val ringBackend: MpvSurfaceBackend? get() = rendering?.backend
    private val surfaceRing: MpvSurfaceConsumer? get() = rendering?.surface
    internal val renderContextLifecycle: MpvRenderContextLifecycle? get() = rendering?.lifecycle

    init {
        currentSurfaceBackend()?.let { attachBackend(it) }
    }

    private fun attachBackend(backend: MpvSurfaceBackend) {
        rendering?.let {
            check(it.backend === backend) {
                "Skiko changed the mpv surface backend from ${it.backend.rendererName} to ${backend.rendererName}. " +
                    "Recreate the player to use the new renderer."
            }
            return
        }
        val surface = backend.createSurfaceConsumer(handle.ptr)
        val lifecycle = backend.createRenderContextLifecycle(
            object : MpvRenderContextHost {
                override val handle: MPVHandle get() = this@MpvMediampPlayer.handle

                override fun hasActivePlaybackSession(): Boolean =
                    this@MpvMediampPlayer.hasActivePlaybackSession()

                override fun onRenderContextReady() = renderContextBecameReady()

                override fun invalidateSurfaceRingForEnvironmentChange() {
                    surface.invalidateForRenderEnvironmentChange()
                }
            },
        )
        lifecycle.initialize()
        rendering = Rendering(backend, surface, lifecycle)
        renderContextBecameReady()
    }

    /** Explicitly creates a windowless render context for headless capture. Idempotent. */
    internal fun createRenderContext(): Boolean {
        if (rendering == null) headlessSurfaceBackend()?.let { attachBackend(it) }
        return ringBackend?.createRenderContext(handle.ptr) ?: false
    }

    internal fun releaseRenderContext(): Boolean =
        ringBackend?.destroyRenderContext(handle.ptr) ?: false

    override fun ensureRenderContextForLoad(): Boolean =
        renderContextLifecycle?.ensureReadyForLoad() ?: !supportsSurfaceBackend()

    /** Returns null until Skiko has chosen its redrawer; loading waits for that selection. */
    internal fun createSkiaInterop(layerRedrawer: SkiaLayerRedrawer): SkiaRenderDeviceInterop? {
        val backend = currentSurfaceBackend(layerRedrawer) ?: return null
        attachBackend(backend)
        return backend.createSkiaInterop(layerRedrawer)
    }

    /** See [MpvSurfaceConsumer.requestSurface]. */
    internal fun requestSurface(width: Int, height: Int, devicePtr: Long): Boolean =
        surfaceRing?.requestSurface(width, height, devicePtr) ?: false

    /** See [MpvSurfaceConsumer.refreshDeviceIfChanged]. */
    internal fun refreshDeviceIfChanged(devicePtr: Long) {
        surfaceRing?.refreshDeviceIfChanged(devicePtr)
    }

    /** See [MpvSurfaceConsumer.currentFrameImage]. Do NOT close the returned image. */
    internal fun currentFrameImage(directContext: DirectContext): Image? =
        surfaceRing?.currentFrameImage(directContext)

    /** See [MpvSurfaceConsumer.release]. */
    internal fun releaseSurface() {
        surfaceRing?.release()
    }

    /** See [MpvSurfaceBackend.readSurfacePixels]. */
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
