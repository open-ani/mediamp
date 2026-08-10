/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import org.openani.mediamp.mpv.MPVLog
import org.openani.mediamp.mpv.utils.OpenGLRenderEnvironment
import org.openani.mediamp.mpv.utils.SkiaOpenGLInterop
import org.openani.mediamp.mpv.utils.SkiaRenderDeviceInterop

/**
 * OpenGL lifecycle (Linux/GLX, Windows/WGL): the producer context must join Skiko's live
 * GL share group, so it can only be created after [attachRenderEnvironment], and
 * `loadfile` is gated on that (`vo=libmpv` requires the render context to exist first).
 */
internal class OpenGLRenderContextLifecycle(
    private val backend: OpenGLSurfaceRingBackend,
    private val host: MpvRenderContextHost,
) : MpvRenderContextLifecycle {

    private var attachedEnvironment: OpenGLRenderEnvironment? = null

    override fun initialize() {
        // Restrict decoding to what this platform's producer context can import; see
        // OpenGLSurfaceRingBackend.hwdecPreference.
        check(host.handle.setPropertyString("hwdec", backend.hwdecPreference))
        // No producer context yet: the live Skiko GL environment must be attached
        // first, otherwise `vo=libmpv` would be asked to load before its required
        // render context can exist.
    }

    override fun createEagerly(): Boolean = false

    override fun ensureReadyForLoad(): Boolean =
        attachedEnvironment != null && backend.createRenderContext(host.handle.ptr)

    override val deferredReadiness: Boolean get() = true

    override fun createDrawResolver(interop: SkiaRenderDeviceInterop): MpvSurfaceDrawResolver =
        OpenGLSurfaceDrawResolver(interop as SkiaOpenGLInterop, this, host)

    override fun captureProvisioning(): MpvRenderContextProvisioning? {
        val environment = attachedEnvironment ?: return null
        return MpvRenderContextProvisioning { handlePtr ->
            check(backend.attachRenderEnvironment(handlePtr, environment)) {
                "Could not attach the main player's OpenGL environment to the preview decoder"
            }
        }
    }

    /** The currently attached GL environment, or null before the first attach. */
    fun currentRenderEnvironment(): OpenGLRenderEnvironment? = attachedEnvironment

    /**
     * Attaches Skiko's current GL share environment. A new identity is a device
     * recreation: native code must discard its old producer context/share group before
     * the next `createRenderContext`, and the ring recreates its consumer wrappers on
     * the next published generation.
     */
    fun attachRenderEnvironment(environment: OpenGLRenderEnvironment): Boolean {
        if (attachedEnvironment?.identity == environment.identity) {
            return backend.createRenderContext(host.handle.ptr)
                .also { if (it) host.onRenderContextReady() }
        }
        if (attachedEnvironment != null) {
            // mpv's render.h states that freeing mpv_render_context while video is active
            // forcibly disables video. Replacing Skiko's GL share group therefore cannot
            // be handled by merely destroying and recreating context B: recovery would
            // require a separately verified video-output reload that preserves position,
            // pause state, selected track, and hwdec state.
            check(!host.hasActivePlaybackSession()) {
                "Skiko replaced its OpenGL share context during active playback. " +
                    "Transparent mpv video-output recovery is not supported."
            }
            // The native OpenGL renderer owns its producer context and must release it
            // before accepting a new Skiko share context. The old context owns its stale
            // FBOs; closing Skia wrappers below makes their texture references disappear.
            host.invalidateSurfaceRingForEnvironmentChange()
            backend.destroyRenderContext(host.handle.ptr)
        }
        val attached = backend.attachRenderEnvironment(host.handle.ptr, environment)
        if (attached) {
            attachedEnvironment = environment
            if (backend.createRenderContext(host.handle.ptr)) host.onRenderContextReady()
        }
        return attached
    }
}

/**
 * OpenGL draw resolver: Skiko may replace its redrawer (and with it the GL share group)
 * at runtime, so every draw re-reads the live snapshot and re-attaches when the identity
 * changed. On Windows the snapshot additionally has to be taken here rather than earlier,
 * because the WGL handles are only observable while Skiko's context is current.
 */
internal class OpenGLSurfaceDrawResolver(
    private val interop: SkiaOpenGLInterop,
    private val lifecycle: OpenGLRenderContextLifecycle,
    private val host: MpvRenderContextHost,
) : MpvSurfaceDrawResolver {
    override val rendererName: String get() = OpenGLSurfaceRingBackend.rendererName

    override fun resolveDrawPass(renderContextReady: Boolean): MpvSurfaceDrawPass? {
        val snapshot = runCatching { interop.renderSnapshot() }
            .onFailure {
                MPVLog.error(host.handle.ptr, "OpenGL render context unavailable; video stays black", it)
            }
            .getOrNull() ?: return null
        val environment = snapshot.environment
        val attached = if (renderContextReady &&
            lifecycle.currentRenderEnvironment()?.identity == environment.identity
        ) {
            true
        } else {
            runCatching { lifecycle.attachRenderEnvironment(environment) }
                .onFailure {
                    MPVLog.error(host.handle.ptr, "OpenGL render context unavailable; video stays black", it)
                }
                .getOrDefault(false)
        }
        if (!attached) return null
        return MpvSurfaceDrawPass(snapshot.directContext) { environment.renderDeviceToken }
    }
}
