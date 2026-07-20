/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import org.jetbrains.skia.DirectContext
import org.openani.mediamp.mpv.utils.SkiaRenderDeviceInterop

/**
 * What one compose draw pass renders against. [directContext] is Skia's current
 * GrDirectContext (null until the window rendered its first frame). [renderDevicePtr] is
 * deliberately a deferred read: the draw pass resolves the consumer render device only
 * after its size checks, against the live redrawer.
 */
internal class MpvSurfaceDrawPass(
    val directContext: DirectContext?,
    val renderDevicePtr: () -> Long?,
)

/**
 * Platform half of the compose draw pass, mirroring [MpvSurfaceRingBackend]: how each
 * draw resolves readiness and Skia's current target. Environment-bound backends may
 * (re)attach their render environment inside [resolveDrawPass]; eager backends only gate
 * on the readiness established when the surface entered composition.
 */
internal interface MpvSurfaceDrawResolver {
    /** Renderer name for the one-time "rendering WxH via X surface" log line. */
    val rendererName: String

    /**
     * Resolves what this draw pass may render with, or null when the render context is
     * not ready. [renderContextReady] is the composable's current readiness state; a
     * non-null result while it is still false means readiness just arrived with this
     * pass.
     */
    fun resolveDrawPass(renderContextReady: Boolean): MpvSurfaceDrawPass?
}

/** Draw resolver for eagerly-created contexts: readiness was decided at composition start. */
internal class EagerSurfaceDrawResolver(
    backend: MpvSurfaceRingBackend,
    private val interop: SkiaRenderDeviceInterop,
) : MpvSurfaceDrawResolver {
    override val rendererName: String = backend.rendererName

    override fun resolveDrawPass(renderContextReady: Boolean): MpvSurfaceDrawPass? {
        if (!renderContextReady) return null
        return MpvSurfaceDrawPass(interop.directContext) {
            runCatching { interop.renderDevicePtr }.getOrNull()
        }
    }
}
