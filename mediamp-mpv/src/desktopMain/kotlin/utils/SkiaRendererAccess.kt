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

/** Resolves legacy redrawers and Skiko 0.153+ frame drivers without caching renderer instances. */
internal class SkiaRendererAccess(layerClass: Class<*>) {
    private val legacyGetter = layerClass.methods.firstOrNull { it.name == "getRedrawer\$skiko" }
    private val driverGetter = if (legacyGetter == null) {
        layerClass.getMethod("getFrameDriver\$skiko")
    } else null
    private var rendererGetter: Method? = null
    private var driverClass: Class<*>? = null

    fun currentRenderer(layer: Any): Any? {
        legacyGetter?.let { return it.invoke(layer) }
        val driver = driverGetter!!.invoke(layer) ?: return null
        val clazz = driver.javaClass
        val getter = if (driverClass == clazz) {
            rendererGetter!!
        } else {
            clazz.getDeclaredMethod("getRenderer").apply { isAccessible = true }.also {
                driverClass = clazz
                rendererGetter = it
            }
        }
        return getter.invoke(driver)
    }
}

internal fun Class<*>.skiaFieldOrNull(name: String): Field? {
    var clazz: Class<*>? = this
    while (clazz != null) {
        try {
            return clazz.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            clazz = clazz.superclass
        }
    }
    return null
}

/** Legacy redrawers own a ContextHandler; new renderers own their DirectContext themselves. */
internal class SkiaDirectContextAccess(rendererClass: Class<*>) {
    private val handler = rendererClass.skiaFieldOrNull("contextHandler")
    private val context = if (handler != null) {
        handler.type.skiaFieldOrNull("context")
    } else {
        sequenceOf("glContext", "context")
            .mapNotNull { rendererClass.skiaFieldOrNull(it) }
            .firstOrNull { DirectContext::class.java.isAssignableFrom(it.type) }
    } ?: error("Unsupported Skiko renderer ${rendererClass.name}: no Skia DirectContext field")

    init {
        check(DirectContext::class.java.isAssignableFrom(context.type)) {
            "Unexpected Skiko DirectContext field type ${context.type.name}"
        }
    }

    fun get(renderer: Any): DirectContext? {
        val owner = if (handler != null) handler.get(renderer) ?: return null else renderer
        return context.get(owner) as DirectContext?
    }
}
