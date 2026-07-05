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
 * Reflective access to the MTLDevice and Skia DirectContext that Skiko's MetalRedrawer
 * uses to render this window. The MTLTexture handed to Skia must be created on the same
 * MTLDevice. All accessed types are internal in Skiko, hence reflection; verified against
 * Skiko 0.9.37 (CMP 1.10).
 */
internal class SkiaMetalInterop(layer: SkiaLayer) {
    private val redrawer: Any = SkiaLayer::class.java
        .getMethod("getRedrawer\$skiko")
        .invoke(layer)
        ?: error("SkiaLayer has no redrawer yet")

    init {
        check(redrawer.javaClass.simpleName == "MetalRedrawer") {
            "Expected MetalRedrawer, got ${redrawer.javaClass.name}. " +
                "The mpv Metal render path requires Skiko's Metal backend (macOS default)."
        }
    }

    private val adapterField = redrawer.javaClass.getDeclaredField("adapter")
        .apply { isAccessible = true }
    private val adapterPtrMethod = adapterField.type.getMethod("getPtr")
    private val contextHandlerField = redrawer.javaClass.getDeclaredField("contextHandler")
        .apply { isAccessible = true }
    private val contextField = Class.forName("org.jetbrains.skiko.context.ContextHandler")
        .getDeclaredField("context")
        .apply { isAccessible = true }

    /** The MTLDevice pointer Skia renders with. */
    val mtlDevicePtr: Long
        get() = adapterPtrMethod.invoke(adapterField.get(redrawer)) as Long

    /** Skia's GrDirectContext; null until the first frame has been rendered. */
    val directContext: DirectContext?
        get() = contextField.get(contextHandlerField.get(redrawer)) as DirectContext?
}
