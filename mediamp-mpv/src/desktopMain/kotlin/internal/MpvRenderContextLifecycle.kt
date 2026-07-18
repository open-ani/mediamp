/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.utils.SkiaRenderDeviceInterop

/**
 * Player-side dependencies of a [MpvRenderContextLifecycle]: the native handle plus the
 * deferred-load hooks of `JvmMpvMediampPlayer`. Implemented by `MpvMediampPlayer`.
 */
internal interface MpvRenderContextHost {
    val handle: MPVHandle

    /** True while a playback session is active; see `JvmMpvMediampPlayer`. */
    fun hasActivePlaybackSession(): Boolean

    /** Completes a load deferred until the render context became ready. */
    fun onRenderContextReady()

    /** Drops consumer-side Skia wraps before the producer replaces its render environment. */
    fun invalidateSurfaceRingForEnvironmentChange()
}

/**
 * Everything a second mpv instance (the frame-preview decoder) must attach before it can
 * create its own render context. Captured from the main player's
 * [MpvRenderContextLifecycle] at session-creation time, so a concurrent environment
 * replacement cannot change what gets attached.
 */
internal fun interface MpvRenderContextProvisioning {
    /** Attaches the captured prerequisites to [handlePtr]; throws when attachment fails. */
    fun prepare(handlePtr: Long)
}

/**
 * Per-player lifecycle of the backend's producer render context: WHEN
 * [MpvSurfaceRingBackend.createRenderContext] may run. Backends that own their producer
 * device ([EagerRenderContextLifecycle]) create it at player construction; backends whose
 * producer context must join an externally owned render environment
 * (`OpenGLRenderContextLifecycle`) create it only once that environment has been attached
 * and gate `loadfile` on it. Chosen by [MpvSurfaceRingBackend], so the shared player and
 * composable contain no platform checks — and platform-specific operations (such as the
 * Linux GLX attach) exist only on the platform implementation, where other platforms
 * cannot call them by mistake.
 */
internal interface MpvRenderContextLifecycle {
    /**
     * One-time backend setup at player construction (after mpv initialization): eager
     * backends create the producer context and start the render thread now;
     * environment-bound backends apply their decode constraints and wait for the attach.
     */
    fun initialize()

    /**
     * Creates the producer context when the compose surface enters composition, returning
     * the initial "render context ready" state. Environment-bound backends return false
     * without touching native state: readiness arrives from the first successful draw
     * pass instead.
     */
    fun createEagerly(): Boolean

    /**
     * Whether the producer context may exist for `loadfile` right now; see
     * `JvmMpvMediampPlayer.ensureRenderContextForLoad`.
     */
    fun ensureReadyForLoad(): Boolean

    /**
     * True when readiness arrives from a draw pass rather than from [createEagerly]. The
     * surface-config loop then waits for readiness before its first request and does not
     * poll after a failed request (the environment went away; a later draw pass
     * re-attaches and state changes retrigger the loop).
     */
    val deferredReadiness: Boolean

    /** The per-draw resolver matching this lifecycle; [interop] comes from the same backend. */
    fun createDrawResolver(interop: SkiaRenderDeviceInterop): MpvSurfaceDrawResolver

    /**
     * Captures what the frame-preview decoder must attach before creating its own render
     * context, or null when that is unavailable (environment-bound backends before their
     * first attach) — the decoder cannot be created then.
     */
    fun captureProvisioning(): MpvRenderContextProvisioning?
}

/**
 * Lifecycle for backends that own their producer device (macOS Metal, Windows D3D11):
 * the render context is created eagerly at player construction and needs nothing from
 * the live Skiko renderer.
 */
internal class EagerRenderContextLifecycle(
    private val backend: MpvSurfaceRingBackend,
    private val host: MpvRenderContextHost,
) : MpvRenderContextLifecycle {
    override fun initialize() {
        backend.createRenderContext(host.handle.ptr)
    }

    override fun createEagerly(): Boolean = backend.createRenderContext(host.handle.ptr)

    // Eager platforms keep the established load behavior even when a headless CI
    // environment cannot create an accelerated producer context.
    override fun ensureReadyForLoad(): Boolean = true

    override val deferredReadiness: Boolean get() = false

    override fun createDrawResolver(interop: SkiaRenderDeviceInterop): MpvSurfaceDrawResolver =
        EagerSurfaceDrawResolver(backend, interop)

    override fun captureProvisioning(): MpvRenderContextProvisioning =
        MpvRenderContextProvisioning { }
}
