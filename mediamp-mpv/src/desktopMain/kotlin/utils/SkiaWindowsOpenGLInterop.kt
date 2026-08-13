/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.utils

import java.lang.reflect.Field
import java.lang.reflect.Method
import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.SkiaLayer

/**
 * Reflective access to Skiko 0.9.37.4's Windows OpenGL redrawer
 * ([WindowsOpenGLRedrawer]), which Compose uses when the render API is OPENGL instead
 * of the Direct3D 12 default.
 *
 * The OpenGL fallback producer is fully independent of Skiko (its frames arrive as CPU
 * copies), so unlike the other interops this one carries no real render device: only
 * Skia's DirectContext (the consumer upload target) and the redrawer's private
 * `context` Long, which serves purely as an identity token for detecting redrawer
 * recreation — its native meaning is never interpreted.
 *
 * The redrawer (and with it the DirectContext) can be recreated by Skiko at runtime,
 * so nothing is cached across calls — every access re-reads the live redrawer from the
 * layer.
 */
internal class SkiaWindowsOpenGLInterop(private val layer: SkiaLayer) : SkiaRenderDeviceInterop {
    private val getRedrawerMethod: Method = SkiaLayer::class.java.getMethod("getRedrawer\$skiko")

    private class RedrawerAccess(redrawerClass: Class<*>) {
        val contextHandlerField: Field = redrawerClass.getDeclaredField("contextHandler")
            .apply { isAccessible = true }
        val contextField: Field = redrawerClass.getDeclaredField("context")
            .apply { isAccessible = true }
        val directContextField: Field = Class.forName("org.jetbrains.skiko.context.ContextHandler")
            .getDeclaredField("context")
            .apply { isAccessible = true }

        init {
            check(contextHandlerField.type.name == "org.jetbrains.skiko.context.OpenGLContextHandler") {
                "Unexpected WindowsOpenGLRedrawer.contextHandler type ${contextHandlerField.type.name}; " +
                    "Skiko 0.9.37.4 OpenGL interop layout changed."
            }
            check(contextField.type == java.lang.Long.TYPE) {
                "Unexpected WindowsOpenGLRedrawer.context type ${contextField.type}; expected long."
            }
            check(DirectContext::class.java.isAssignableFrom(directContextField.type)) {
                "Unexpected ContextHandler.context type ${directContextField.type}; expected DirectContext."
            }
        }
    }

    private var cachedAccess: RedrawerAccess? = null
    private var cachedAccessClass: Class<*>? = null

    private fun currentRedrawer(): Any {
        val redrawer = getRedrawerMethod.invoke(layer) ?: error(
            "SkiaLayer has no redrawer yet. Attach the player after the Compose window is visible."
        )
        check(redrawer.javaClass.name == WINDOWS_OPENGL_REDRAWER) {
            "Unsupported Skiko redrawer ${redrawer.javaClass.name}. The mpv OpenGL fallback " +
                "on Windows requires Skiko's WindowsOpenGLRedrawer, which Compose only uses when " +
                "SKIKO_RENDER_API/skiko.renderApi is OPENGL. Unset it to use the Direct3D render " +
                "path instead (mediamp then drives mpv through D3D11)."
        }
        return redrawer
    }

    private fun accessFor(redrawer: Any): RedrawerAccess {
        val clazz = redrawer.javaClass
        cachedAccess?.let { if (cachedAccessClass == clazz) return it }
        return RedrawerAccess(clazz).also {
            cachedAccess = it
            cachedAccessClass = clazz
        }
    }

    /** The redrawer's own context token — an identity, not a usable device pointer. */
    override val renderDevicePtr: Long
        get() {
            val redrawer = currentRedrawer()
            return accessFor(redrawer).contextField.getLong(redrawer)
        }

    /** Skia's live GrDirectContext; null before its first render. */
    override val directContext: DirectContext?
        get() {
            val redrawer = currentRedrawer()
            val access = accessFor(redrawer)
            return access.directContextField.get(access.contextHandlerField.get(redrawer)) as DirectContext?
        }

    companion object {
        private const val WINDOWS_OPENGL_REDRAWER = "org.jetbrains.skiko.redrawer.WindowsOpenGLRedrawer"
    }
}
