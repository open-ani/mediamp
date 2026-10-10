/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.utils

import org.jetbrains.skia.DirectContext
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Reflective access to the MTLDevice and Skia DirectContext that Skiko uses to render
 * this window. The MTLTexture handed to Skia must be created on the same MTLDevice,
 * and the Skia surface must be created against the *current* DirectContext.
 *
 * The redrawer (and with it the DirectContext) can be recreated by Skiko at runtime
 * (RedrawerManager fallbacks, display changes), so nothing is cached across calls —
 * every access re-reads the live redrawer from the layer.
 *
 * Supports both of Skiko's Metal render paths (verified against Skiko 0.9.37 / CMP 1.10):
 * [MetalRedrawer] (AWT window rendering) and [MetalSwingRedrawer] (offscreen swing interop).
 *
 * Skiko leaves Metal for a software redrawer when it has no usable Metal device, also at
 * runtime after a failed frame. Such a redrawer has no device to share: [renderDevicePtr]
 * is then 0, which turns the consumer to CPU frames (see `MacosSurfaceConsumer`), and
 * [directContext] is null. Both are read inside the Compose draw pass, where an exception
 * stops the whole window from rendering, so neither throws for a non-Metal redrawer.
 */
internal class SkiaMetalInterop(private val layerRedrawer: SkiaLayerRedrawer) : SkiaRenderDeviceInterop {

    private class RedrawerAccess(redrawerClass: Class<*>) {
        val adapterField: Field = redrawerClass.getDeclaredField("adapter")
            .apply { isAccessible = true }
        val adapterPtrMethod: Method = adapterField.type.getMethod("getPtr")

        // MetalRedrawer keeps the DirectContext inside its ContextHandler; MetalSwingRedrawer
        // holds it directly in a `context` field.
        val contextHandlerField: Field? = runCatching {
            redrawerClass.getDeclaredField("contextHandler").apply { isAccessible = true }
        }.getOrNull()
        val contextHandlerContextField: Field? = contextHandlerField?.let {
            Class.forName("org.jetbrains.skiko.context.ContextHandler")
                .getDeclaredField("context")
                .apply { isAccessible = true }
        }
        val directContextField: Field? = runCatching {
            redrawerClass.getDeclaredField("context").apply { isAccessible = true }
        }.getOrNull()

        init {
            check(contextHandlerField != null || directContextField != null) {
                "Unsupported Skiko redrawer ${redrawerClass.name}. " +
                    "The mpv Metal render path requires Skiko's Metal backend (macOS default)."
            }
        }
    }

    private var cachedAccess: RedrawerAccess? = null
    private var cachedAccessClass: Class<*>? = null

    private fun accessFor(redrawer: Any): RedrawerAccess {
        val clazz = redrawer.javaClass
        cachedAccess?.let { if (cachedAccessClass == clazz) return it }
        return RedrawerAccess(clazz).also {
            cachedAccess = it
            cachedAccessClass = clazz
        }
    }

    /** The MTLDevice pointer Skia renders with, or 0 when Skiko is not rendering with Metal. */
    override val renderDevicePtr: Long
        get() {
            val redrawer = layerRedrawer.redrawer
            if (!isSkikoMetalRedrawer(redrawer.javaClass)) return 0L
            val access = accessFor(redrawer)
            return access.adapterPtrMethod.invoke(access.adapterField.get(redrawer)) as Long
        }

    /**
     * Skia's *current* GrDirectContext; null until the first frame has been rendered, and
     * while Skiko is not rendering with Metal.
     */
    override val directContext: DirectContext?
        get() {
            val redrawer = layerRedrawer.redrawerOrNull ?: return null
            if (!isSkikoMetalRedrawer(redrawer.javaClass)) return null
            val access = accessFor(redrawer)
            return when {
                access.contextHandlerField != null ->
                    access.contextHandlerContextField!!
                        .get(access.contextHandlerField.get(redrawer)) as DirectContext?

                else -> access.directContextField!!.get(redrawer) as DirectContext?
            }
        }
}

/** Whether [redrawerClass] is one of Skiko's Metal renderers, whose MTLDevice the ring can share. */
internal fun isSkikoMetalRedrawer(redrawerClass: Class<*>): Boolean = when (redrawerClass.name) {
    "org.jetbrains.skiko.redrawer.MetalRedrawer",
    "org.jetbrains.skiko.swing.MetalSwingRedrawer",
    -> true

    else -> false
}
