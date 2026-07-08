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
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Reflective access to the Direct3D 12 device and Skia DirectContext that Skiko uses to
 * render this window (Compose's default Windows backend, [Direct3DRedrawer]).
 *
 * The `device` field is a pointer to Skiko's native C++ `DirectXDevice` struct
 * (directXRedrawer.cc), not a raw ID3D12Device — the native side (render_d3d11.cpp)
 * extracts and QueryInterface-verifies the ID3D12Device from it before use.
 *
 * The redrawer (and with it the DirectContext) can be recreated by Skiko at runtime,
 * so nothing is cached across calls — every access re-reads the live redrawer from the
 * layer. Verified against Skiko 0.9.37 / CMP 1.10.
 */
internal class SkiaDirectXInterop(private val layer: SkiaLayer) : SkiaRenderDeviceInterop {
    private val getRedrawerMethod: Method = SkiaLayer::class.java.getMethod("getRedrawer\$skiko")

    private class RedrawerAccess(redrawerClass: Class<*>) {
        val deviceField: Field = redrawerClass.getDeclaredField("device")
            .apply { isAccessible = true }
        val contextHandlerField: Field = redrawerClass.getDeclaredField("contextHandler")
            .apply { isAccessible = true }
        val contextHandlerContextField: Field =
            Class.forName("org.jetbrains.skiko.context.ContextHandler")
                .getDeclaredField("context")
                .apply { isAccessible = true }

        init {
            check(deviceField.type == java.lang.Long.TYPE) {
                "Unexpected Direct3DRedrawer.device type ${deviceField.type}"
            }
        }
    }

    private var cachedAccess: RedrawerAccess? = null
    private var cachedAccessClass: Class<*>? = null

    private fun currentRedrawer(): Any {
        val redrawer = getRedrawerMethod.invoke(layer) ?: error("SkiaLayer has no redrawer")
        check(redrawer.javaClass.name == "org.jetbrains.skiko.redrawer.Direct3DRedrawer") {
            "Unsupported Skiko redrawer ${redrawer.javaClass.name}. The mpv D3D11 render " +
                "path requires Skiko's Direct3D backend (Windows default; do not set " +
                "SKIKO_RENDER_API/skiko.renderApi to another backend)."
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

    /** Pointer to Skiko's native DirectXDevice struct. */
    override val renderDevicePtr: Long
        get() {
            val redrawer = currentRedrawer()
            return accessFor(redrawer).deviceField.getLong(redrawer)
        }

    override val directContext: DirectContext?
        get() {
            val redrawer = currentRedrawer()
            val access = accessFor(redrawer)
            return access.contextHandlerContextField
                .get(access.contextHandlerField.get(redrawer)) as DirectContext?
        }
}
