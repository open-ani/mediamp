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
import kotlinx.coroutines.withContext
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.internal.MpvRenderContextHost
import org.openani.mediamp.mpv.internal.MpvRenderContextLifecycle
import org.openani.mediamp.mpv.internal.MpvSurfaceBackend
import org.openani.mediamp.mpv.internal.MpvSurfaceConsumer
import org.openani.mediamp.mpv.internal.OpenGLSurfaceRingBackend
import org.openani.mediamp.mpv.internal.currentSurfaceBackend
import org.openani.mediamp.mpv.internal.headlessSurfaceBackend
import org.openani.mediamp.mpv.internal.runOnAwtEventThreadAndWait
import org.openani.mediamp.mpv.internal.supportsSurfaceBackend
import org.openani.mediamp.mpv.internal.writeFramePng
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
    /**
     * Optional hook to customize mpv options (e.g. `demuxer-max-bytes`, `cache-secs`) right
     * before the native handle is initialized. See [JvmMpvMediampPlayer] for details.
     */
    configureOptions: ((MPVHandle) -> Unit)? = null,
) : JvmMpvMediampPlayer(context, parentCoroutineContext, mainDispatcher, configureOptions) {

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

    /**
     * Raised on the machine thread before native teardown is scheduled. Compose disposal
     * may arrive later, after the handle has already been finalized, so every surface entry
     * point must become a no-op as soon as this flag is visible.
     */
    @Volatile
    private var surfaceTeardownStarted = false

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
        if (surfaceTeardownStarted) return false
        if (rendering == null) headlessSurfaceBackend()?.let { attachBackend(it) }
        return ringBackend?.createRenderContext(handle.ptr) ?: false
    }

    internal fun releaseRenderContext(): Boolean =
        !surfaceTeardownStarted && (ringBackend?.destroyRenderContext(handle.ptr) ?: false)

    override fun ensureRenderContextForLoad(): Boolean =
        !surfaceTeardownStarted && (renderContextLifecycle?.ensureReadyForLoad() ?: !supportsSurfaceBackend())

    /** Returns null until Skiko has chosen its redrawer; loading waits for that selection. */
    internal fun createSkiaInterop(layerRedrawer: SkiaLayerRedrawer): SkiaRenderDeviceInterop? {
        if (surfaceTeardownStarted) return null
        val backend = currentSurfaceBackend(layerRedrawer) ?: return null
        val interop = backend.createSkiaInterop(layerRedrawer)
        if (rendering == null) {
            // Before attachBackend: eager lifecycles create the producer device there.
            runCatching { interop.renderDevicePtr }.getOrNull()
                ?.let { backend.hintConsumerDevice(handle.ptr, it) }
        }
        attachBackend(backend)
        return interop
    }

    /** See [MpvSurfaceConsumer.requestSurface]. */
    internal fun requestSurface(width: Int, height: Int, devicePtr: Long): Boolean =
        !surfaceTeardownStarted && (surfaceRing?.requestSurface(width, height, devicePtr) ?: false)

    /** See [MpvSurfaceConsumer.refreshDeviceIfChanged]. */
    internal fun refreshDeviceIfChanged(devicePtr: Long) {
        if (!surfaceTeardownStarted) surfaceRing?.refreshDeviceIfChanged(devicePtr)
    }

    /** See [MpvSurfaceConsumer.currentFrameImage]. Do NOT close the returned image. */
    internal fun currentFrameImage(directContext: DirectContext?): Image? =
        if (surfaceTeardownStarted) null else surfaceRing?.currentFrameImage(directContext)

    /** See [MpvSurfaceConsumer.release]. */
    internal fun releaseSurface() {
        if (!surfaceTeardownStarted) surfaceRing?.release()
    }

    /** See [MpvSurfaceBackend.readSurfacePixels]. */
    internal fun readSurfacePixels(dims: IntArray): IntArray? =
        if (surfaceTeardownStarted) null else ringBackend?.readSurfacePixels(handle.ptr, dims)

    internal fun isSurfaceTeardownStarted(): Boolean = surfaceTeardownStarted

    internal override fun setRenderUpdateListener(listener: RenderUpdateListener?): Boolean =
        !surfaceTeardownStarted && super.setRenderUpdateListener(listener)

    override fun nativeTeardownStarting() {
        surfaceTeardownStarted = true
    }

    override fun prepareNativeTeardown() {
        val ptr = handle.ptr
        runOnAwtEventThreadAndWait {
            // Skia wrappers belong to Skiko's consumer render thread. Closing them here also
            // establishes an event-queue barrier after any draw/swap already in progress.
            surfaceRing?.release()

            val backend = ringBackend
            if (backend === OpenGLSurfaceRingBackend) {
                // Skiko and mediamp borrow the same Xlib Display. Mesa's DRI3 GLX teardown
                // can deadlock when glXDestroyContext/glXDestroyPbuffer runs concurrently
                // with Skiko's swapBuffers. The queued AWT event serializes both operations.
                backend.destroyRenderContext(ptr)
            }
        }
    }

    /**
     * Renders the current frame once more at the video's display size (`dwidth` x
     * `dheight`: rotation and sample aspect ratio applied) on the render thread and writes
     * it as PNG. The ring, sized to the consumer, is not involved: the image is the video
     * at its own resolution and has no letterbox margins, and headless capture needs no
     * ring either. mpv's own screenshot command is the fallback; it cannot convert hwdec
     * frames without zimg.
     */
    override suspend fun takeScreenshotImpl(path: String): Boolean {
        val backend = ringBackend?.takeUnless { surfaceTeardownStarted } ?: return super.takeScreenshotImpl(path)
        val width = handle.getPropertyInt("dwidth")
        val height = handle.getPropertyInt("dheight")
        if (width <= 0 || height <= 0) return super.takeScreenshotImpl(path)
        val saved = withContext(Dispatchers.IO) {
            val pixels = backend.renderFramePixels(handle.ptr, width, height) ?: return@withContext false
            writeFramePng(pixels, width, height, path)
        }
        return saved || super.takeScreenshotImpl(path)
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
