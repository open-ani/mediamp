/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.utils

import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.SkiaLayer

/**
 * Reflective access to the MTLDevice and Skia DirectContext that Skiko uses to render
 * this window. The MTLTexture handed to Skia must be created on the same MTLDevice.
 *
 * Supports both of Skiko's Metal render paths (verified against Skiko 0.9.37 / CMP 1.10):
 * - [MetalRedrawer] (AWT window rendering, the ComposeWindow default), and
 * - [MetalSwingRedrawer] (offscreen swing interop rendering, e.g. ComposePanel or
 *   `compose.swing.render.on.graphics=true`).
 */
internal class SkiaMetalInterop(layer: SkiaLayer) {
    private val redrawer: Any = SkiaLayer::class.java
        .getMethod("getRedrawer\$skiko")
        .invoke(layer)
        ?: error("SkiaLayer has no redrawer yet")

    private val adapterField = redrawer.javaClass.getDeclaredField("adapter")
        .apply { isAccessible = true }
    private val adapterPtrMethod = adapterField.type.getMethod("getPtr")

    // MetalRedrawer keeps the DirectContext inside its ContextHandler; MetalSwingRedrawer
    // holds it directly in a `context` field.
    private val contextHandlerField = runCatching {
        redrawer.javaClass.getDeclaredField("contextHandler").apply { isAccessible = true }
    }.getOrNull()
    private val contextHandlerContextField = contextHandlerField?.let {
        Class.forName("org.jetbrains.skiko.context.ContextHandler")
            .getDeclaredField("context")
            .apply { isAccessible = true }
    }
    private val directContextField = runCatching {
        redrawer.javaClass.getDeclaredField("context").apply { isAccessible = true }
    }.getOrNull()

    init {
        check(contextHandlerField != null || directContextField != null) {
            "Unsupported Skiko redrawer ${redrawer.javaClass.name}. " +
                "The mpv Metal render path requires Skiko's Metal backend (macOS default)."
        }
    }

    /** The MTLDevice pointer Skia renders with. */
    val mtlDevicePtr: Long
        get() = adapterPtrMethod.invoke(adapterField.get(redrawer)) as Long

    /** Skia's GrDirectContext; null until the first frame has been rendered. */
    val directContext: DirectContext?
        get() = when {
            contextHandlerField != null ->
                contextHandlerContextField!!.get(contextHandlerField.get(redrawer)) as DirectContext?

            else -> directContextField!!.get(redrawer) as DirectContext?
        }
}
