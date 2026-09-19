/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.utils

import org.jetbrains.skiko.SkiaLayer
import java.lang.reflect.Proxy
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class SkiaLayerRedrawerTest {
    @Test
    fun `access follows redrawer initialization replacement and removal`() {
        SwingUtilities.invokeAndWait {
            val layer = SkiaLayer()
            val liveLayer = SkiaLayerRedrawer(layer)
            val managerField = SkiaLayer::class.java.getDeclaredField("redrawerManager")
                .apply { isAccessible = true }
            val manager = managerField.get(layer)
            val redrawerField = manager.javaClass.getDeclaredField("redrawer")
                .apply { isAccessible = true }
            val original = redrawerField.get(manager)
            val redrawerType = Class.forName("org.jetbrains.skiko.redrawer.Redrawer")
            fun newRedrawer(): Any = Proxy.newProxyInstance(
                javaClass.classLoader, arrayOf(redrawerType),
            ) { _, _, _ -> null }

            try {
                redrawerField.set(manager, null)
                assertNull(liveLayer.redrawerOrNull)
                assertFailsWith<IllegalStateException> { liveLayer.redrawer }

                val first = newRedrawer()
                redrawerField.set(manager, first)
                assertSame(first, liveLayer.redrawer)
                val replacement = newRedrawer()
                redrawerField.set(manager, replacement)
                assertSame(replacement, liveLayer.redrawer)

                redrawerField.set(manager, null)
                assertNull(liveLayer.redrawerOrNull)
                assertSame(layer, liveLayer.layer)
            } finally {
                redrawerField.set(manager, original)
                layer.dispose()
            }
        }
    }
}
