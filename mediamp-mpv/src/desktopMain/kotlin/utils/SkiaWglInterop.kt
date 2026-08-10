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
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.nAttachRenderEnvironmentOpenGLWindows
import org.openani.mediamp.mpv.nCurrentRenderEnvironmentOpenGLWindows

/**
 * One live Skiko WGL share group: the `HDC`/`HGLRC` pair that was current on the Compose
 * render thread when this environment was read, plus the redrawer-owned token that
 * detects redrawer recreation.
 *
 * [deviceContext] and [shareContext] come from `wglGetCurrentDC`/`wglGetCurrentContext`
 * rather than from Skiko's private fields: `WindowsOpenGLRedrawer` stores both as opaque
 * `Long`s whose native meaning is an implementation detail, while the current pair is
 * exactly what the driver reports for the context Skia is drawing with.
 */
internal data class WglRenderEnvironment(
    val deviceContext: Long,
    val shareContext: Long,
    /** `WindowsOpenGLRedrawer.context`; changes when Skiko recreates the redrawer. */
    val redrawerToken: Long,
) : OpenGLRenderEnvironment {
    init {
        require(deviceContext != 0L) { "no WGL device context is current on this thread" }
        require(shareContext != 0L) { "no WGL render context is current on this thread" }
    }

    override val identity: Any
        get() = WglRenderEnvironmentIdentity(shareContext, redrawerToken)

    override val renderDeviceToken: Long get() = redrawerToken

    @OptIn(InternalMediampApi::class)
    override fun attach(handlePtr: Long): Boolean = nAttachRenderEnvironmentOpenGLWindows(
        handlePtr,
        deviceContext,
        shareContext,
        // The producer only has to notice that the share group changed; the redrawer
        // token alone would repeat across redrawers if the driver reused the HGLRC.
        shareContext xor (redrawerToken shl 17) xor (deviceContext shl 33),
    )
}

internal data class WglRenderEnvironmentIdentity(
    val shareContext: Long,
    val redrawerToken: Long,
)

/**
 * Reflective access to Skiko 0.9.37.4's Windows OpenGL redrawer
 * (`WindowsOpenGLRedrawer`), which Compose uses when the render API is OPENGL instead of
 * the Direct3D 12 default.
 *
 * Only the DirectContext and the redrawer-identity token are reflected; the actual WGL
 * handles are read natively from the calling thread, so nothing here depends on how
 * Skiko encodes them. That read only succeeds while Skiko's context is current, i.e.
 * inside a Compose draw pass — which is exactly when [renderSnapshot] is called.
 */
internal class SkiaWglInterop(private val layer: SkiaLayer) : SkiaOpenGLInterop {
    private val getRedrawerMethod: Method = SkiaLayer::class.java.getMethod("getRedrawer\$skiko")

    private class RedrawerAccess(redrawerClass: Class<*>) {
        val contextHandlerField: Field = redrawerClass.getDeclaredField("contextHandler")
            .apply { isAccessible = true }
        val wglContextField: Field = redrawerClass.getDeclaredField("context")
            .apply { isAccessible = true }
        val directContextField: Field = Class.forName("org.jetbrains.skiko.context.ContextHandler")
            .getDeclaredField("context")
            .apply { isAccessible = true }

        init {
            check(contextHandlerField.type.name == "org.jetbrains.skiko.context.OpenGLContextHandler") {
                "Unexpected WindowsOpenGLRedrawer.contextHandler type ${contextHandlerField.type.name}; " +
                    "Skiko 0.9.37.4 OpenGL interop layout changed."
            }
            check(wglContextField.type == java.lang.Long.TYPE) {
                "Unexpected WindowsOpenGLRedrawer.context type ${wglContextField.type}; expected long HGLRC."
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
            "Unsupported Skiko redrawer ${redrawer.javaClass.name}. The mpv OpenGL render path " +
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

    @OptIn(InternalMediampApi::class)
    override fun renderSnapshot(): OpenGLRenderSnapshot {
        val redrawer = currentRedrawer()
        val access = accessFor(redrawer)
        val current = nCurrentRenderEnvironmentOpenGLWindows() ?: error(
            "Skiko's OpenGL context is not current on this thread; the mpv WGL producer context " +
                "can only join it from a Compose draw pass."
        )
        return OpenGLRenderSnapshot(
            environment = WglRenderEnvironment(
                deviceContext = current[0],
                shareContext = current[1],
                redrawerToken = access.wglContextField.getLong(redrawer),
            ),
            directContext = access.directContextField
                .get(access.contextHandlerField.get(redrawer)) as DirectContext?,
        )
    }

    /**
     * The redrawer's own context token. Read reflectively rather than from the driver
     * because the surface-config loop needs it outside a draw pass, where no WGL context
     * is current.
     */
    override val renderDevicePtr: Long
        get() {
            val redrawer = currentRedrawer()
            return accessFor(redrawer).wglContextField.getLong(redrawer)
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
