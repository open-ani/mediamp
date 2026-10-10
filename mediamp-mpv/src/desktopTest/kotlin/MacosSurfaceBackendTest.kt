/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import org.jetbrains.skiko.SkiaLayer
import org.openani.mediamp.mpv.utils.SkiaLayerRedrawer
import org.openani.mediamp.mpv.utils.SkiaMetalInterop
import org.openani.mediamp.mpv.utils.isSkikoMetalRedrawer
import java.lang.reflect.Proxy
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacosSurfaceBackendTest {
    private fun redrawerClass(name: String): Class<*> =
        Class.forName("org.jetbrains.skiko.$name", false, javaClass.classLoader)

    @Test
    fun `Metal redrawers share their device with the IOSurface ring`() {
        assertTrue(isSkikoMetalRedrawer(redrawerClass("redrawer.MetalRedrawer")))
        assertTrue(isSkikoMetalRedrawer(redrawerClass("swing.MetalSwingRedrawer")))
    }

    @Test
    fun `software redrawers have no Metal device`() {
        for (name in listOf("redrawer.SoftwareRedrawer", "swing.SoftwareSwingRedrawer")) {
            assertFalse(isSkikoMetalRedrawer(redrawerClass(name)), name)
        }
    }

    /**
     * The interop is read inside the Compose draw pass: for a redrawer without a Metal
     * device it must report none (device 0 selects CPU frames) rather than throw, which
     * would stop the whole window from rendering.
     */
    @Test
    fun `Metal interop reports no device for a non-Metal redrawer instead of throwing`() {
        SwingUtilities.invokeAndWait {
            val layer = SkiaLayer()
            val managerField = SkiaLayer::class.java.getDeclaredField("redrawerManager")
                .apply { isAccessible = true }
            val manager = managerField.get(layer)
            val redrawerField = manager.javaClass.getDeclaredField("redrawer")
                .apply { isAccessible = true }
            val original = redrawerField.get(manager)
            val redrawerType = Class.forName("org.jetbrains.skiko.redrawer.Redrawer")
            val softwareLike = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(redrawerType)) { _, _, _ -> null }
            val interop = SkiaMetalInterop(SkiaLayerRedrawer(layer))

            try {
                redrawerField.set(manager, softwareLike)
                assertEquals(0L, interop.renderDevicePtr)
                assertNull(interop.directContext)

                // Mid-replacement there is no redrawer at all: no context, and the device
                // is not known yet (callers retry), but still nothing thrown from a draw.
                redrawerField.set(manager, null)
                assertNull(interop.directContext)
                assertFailsWith<IllegalStateException> { interop.renderDevicePtr }
            } finally {
                redrawerField.set(manager, original)
                layer.dispose()
            }
        }
    }
}
