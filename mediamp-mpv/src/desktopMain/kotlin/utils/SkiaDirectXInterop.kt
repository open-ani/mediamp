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

/**
 * Reflective access to the Direct3D 12 device and Skia DirectContext that Skiko uses to
 * render this window (Compose's default Windows backend, Direct3DRedrawer/Direct3DRenderer).
 *
 * The `device` field is a pointer to Skiko's native C++ `DirectXDevice` struct
 * (directXRedrawer.cc/directXRenderer.cc), not a raw ID3D12Device — the native side
 * (skiko_directx_device.cpp) validates the shared adapter/device prefix using
 * QueryInterface and matching adapter LUIDs before use.
 *
 * The redrawer (and with it the DirectContext) can be recreated by Skiko at runtime,
 * so nothing is cached across calls — every access re-reads the live redrawer from the
 * layer. The native prefix is source-checked against Skiko 0.9.37 and 0.153.0;
 * Windows rendering still needs regression testing when upgrading Skiko.
 */
internal class SkiaDirectXInterop(
    private val layerRedrawer: SkiaLayerRedrawer,
) : SkiaRenderDeviceInterop {
    private class RedrawerAccess(
        redrawerClass: Class<*>,
    ) {
        val deviceField: Field =
            redrawerClass
                .getDeclaredField("device")
                .apply { isAccessible = true }
        val directContext = SkiaDirectContextAccess(redrawerClass)

        init {
            check(deviceField.type == java.lang.Long.TYPE) {
                "Unexpected ${redrawerClass.simpleName}.device type ${deviceField.type}"
            }
        }
    }

    private var cachedAccess: RedrawerAccess? = null
    private var cachedAccessClass: Class<*>? = null

    private fun currentRedrawer(): Any {
        val redrawer = layerRedrawer.redrawer
        check(
            redrawer.javaClass.name in
                setOf(
                    "org.jetbrains.skiko.redrawer.Direct3DRedrawer",
                    "org.jetbrains.skiko.renderer.Direct3DRenderer",
                ),
        ) {
            "Unsupported Skiko redrawer ${redrawer.javaClass.name}. The mpv D3D11 render " +
                "path requires Skiko's Direct3D backend (Direct3DRedrawer/Direct3DRenderer). " +
                "Recreate the player if Skiko changed its render backend."
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
            return access.directContext.get(redrawer)
        }
}