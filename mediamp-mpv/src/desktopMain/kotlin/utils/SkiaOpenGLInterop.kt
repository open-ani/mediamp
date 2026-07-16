/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.utils

import java.awt.Component
import java.lang.reflect.Field
import java.lang.reflect.Method
import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.SkiaLayer

/**
 * The GLX identities that must belong to one live Skiko redrawer. Native code obtains
 * the corresponding `Display*` through JAWT from [component] during attachment; it must
 * not retain the Java component. The values are deliberately not a raw native pointer:
 * their lifetime is tied to [SkiaOpenGLInterop] and changes with redrawer recreation.
 */
internal data class OpenGLRenderEnvironment(
    val component: Component,
    val shareContext: Long,
    val drawable: Long,
    val window: Long,
) {
    init {
        require(shareContext != 0L) { "Skiko LinuxOpenGLRedrawer has no GLX context yet" }
        require(drawable != 0L) { "Skiko HardwareLayer has no X11 drawable yet" }
    }

    /** Stable only for the lifetime of this GLX share group. */
    val identity: OpenGLRenderEnvironmentIdentity
        get() = OpenGLRenderEnvironmentIdentity(shareContext)
}

internal data class OpenGLRenderEnvironmentIdentity(
    val shareContext: Long,
)

/** Values read from one live redrawer for use during a single draw. */
internal data class OpenGLRenderSnapshot(
    val environment: OpenGLRenderEnvironment,
    val directContext: DirectContext?,
)

/**
 * Reflective access to Skiko 0.9.37.4's Linux GLX redrawer. Skiko has no public API for
 * its GLX context or DirectContext, so this probes the live redrawer on every access and
 * caches reflection metadata only by redrawer class. Do not cache returned contexts or
 * environments: a redrawer replacement is a device-generation change.
 *
 * In Skiko 0.9.37.4 the reflected `context: Long` is a native `GLXContext*` storage
 * address, not the GLXContext value itself. Native attachment deliberately performs the
 * same single dereference as Skiko's makeCurrent/destroyContext JNI implementations.
 */
internal class SkiaOpenGLInterop(private val layer: SkiaLayer) : SkiaRenderDeviceInterop {
    private val getRedrawerMethod: Method = SkiaLayer::class.java.getMethod("getRedrawer\$skiko")
    private val getBackedLayerMethod: Method = SkiaLayer::class.java.getMethod("getBackedLayer\$skiko")
    private val getContentHandleMethod: Method = SkiaLayer::class.java.getMethod("getContentHandle")
    private val getWindowHandleMethod: Method = SkiaLayer::class.java.getMethod("getWindowHandle")

    private class RedrawerAccess(redrawerClass: Class<*>) {
        val contextHandlerField: Field = redrawerClass.getDeclaredField("contextHandler")
            .apply { isAccessible = true }
        val glxContextField: Field = redrawerClass.getDeclaredField("context")
            .apply { isAccessible = true }
        val directContextField: Field = Class.forName("org.jetbrains.skiko.context.ContextHandler")
            .getDeclaredField("context")
            .apply { isAccessible = true }

        init {
            check(contextHandlerField.type.name == "org.jetbrains.skiko.context.OpenGLContextHandler") {
                "Unexpected LinuxOpenGLRedrawer.contextHandler type ${contextHandlerField.type.name}; " +
                    "Skiko 0.9.37.4 OpenGL interop layout changed."
            }
            check(glxContextField.type == java.lang.Long.TYPE) {
                "Unexpected LinuxOpenGLRedrawer.context type ${glxContextField.type}; expected long GLXContext."
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
        check(redrawer.javaClass.name == LINUX_OPENGL_REDRAWER) {
            "Unsupported Skiko redrawer ${redrawer.javaClass.name}. Linux mpv texture sharing " +
                "requires Skiko's LinuxOpenGLRedrawer (GLX/X11). The active renderer is likely " +
                "software; remove SKIKO_RENDER_API/skiko.renderApi software overrides and run Compose " +
                "through X11 or XWayland."
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

    private fun renderEnvironment(redrawer: Any, access: RedrawerAccess): OpenGLRenderEnvironment {
        val component = getBackedLayerMethod.invoke(layer) as? Component ?: error(
            "SkiaLayer backedLayer is not an AWT Component; cannot obtain the GLX Display through JAWT."
        )
        return OpenGLRenderEnvironment(
            component = component,
            shareContext = access.glxContextField.getLong(redrawer),
            drawable = getContentHandleMethod.invoke(layer) as Long,
            window = getWindowHandleMethod.invoke(layer) as Long,
        )
    }

    /** A live snapshot for native share-context creation. */
    fun renderEnvironment(): OpenGLRenderEnvironment {
        val redrawer = currentRedrawer()
        return renderEnvironment(redrawer, accessFor(redrawer))
    }

    /** GLX and Skia values from the same redrawer, valid for the current draw only. */
    fun renderSnapshot(): OpenGLRenderSnapshot {
        val redrawer = currentRedrawer()
        val access = accessFor(redrawer)
        return OpenGLRenderSnapshot(
            environment = renderEnvironment(redrawer, access),
            directContext = access.directContextField
                .get(access.contextHandlerField.get(redrawer)) as DirectContext?,
        )
    }

    /** The GLX-context identity, suitable for detecting redrawer/share-group recreation. */
    override val renderDevicePtr: Long
        get() = renderEnvironment().shareContext

    /** Skia's live GrDirectContext; null before its first render. */
    override val directContext: DirectContext?
        get() {
            val redrawer = currentRedrawer()
            val access = accessFor(redrawer)
            return access.directContextField.get(access.contextHandlerField.get(redrawer)) as DirectContext?
        }

    companion object {
        private const val LINUX_OPENGL_REDRAWER = "org.jetbrains.skiko.redrawer.LinuxOpenGLRedrawer"
    }
}
