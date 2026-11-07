/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import org.junit.Assume.assumeFalse
import org.openani.mediamp.mpv.internal.D3D11SurfaceRingBackend
import org.openani.mediamp.mpv.internal.WindowsOpenGLSurfaceBackend
import org.openani.mediamp.mpv.internal.windowsSurfaceBackend
import org.openani.mediamp.mpv.utils.SkiaDirectXInterop
import org.openani.mediamp.mpv.utils.SkiaLayerRedrawer
import org.openani.mediamp.mpv.utils.isSkikoDirect3DRedrawer
import java.awt.GraphicsEnvironment
import java.lang.reflect.Proxy
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WindowsSurfaceBackendTest {
    private fun redrawerClass(name: String): Class<*> =
        runCatching { Class.forName("org.jetbrains.skiko.redrawer.$name", false, javaClass.classLoader) }
            .getOrElse {
                Class.forName("org.jetbrains.skiko.renderer.${name.removeSuffix("Redrawer")}Renderer",
                    false, javaClass.classLoader)
            }

    @Test
    fun `wait for a redrawer instead of guessing the Windows backend`() {
        assertNull(windowsSurfaceBackend(null))
    }

    @Test
    fun `Direct3D redrawer selects shared D3D11 textures`() {
        assertSame(D3D11SurfaceRingBackend, windowsSurfaceBackend(redrawerClass("Direct3DRedrawer")))
    }

    @Test
    fun `actual OpenGL redrawer selects readback even when Direct3D was requested`() {
        val previous = System.getProperty("skiko.renderApi")
        try {
            System.setProperty("skiko.renderApi", "DIRECT3D")
            assertSame(WindowsOpenGLSurfaceBackend, windowsSurfaceBackend(redrawerClass("WindowsOpenGLRedrawer")))
        } finally {
            if (previous == null) System.clearProperty("skiko.renderApi")
            else System.setProperty("skiko.renderApi", previous)
        }
    }

    /**
     * Software and ANGLE share the D3D11 producer with Direct3D; its consumer takes CPU
     * frames for them (device 0), so Skiko leaving Direct3D during playback needs no
     * backend replacement.
     */
    @Test
    fun `software and ANGLE redrawers share the D3D11 producer`() {
        for (name in listOf("WindowsSoftwareRedrawer", "SoftwareRedrawer", "AngleRedrawer")) {
            assertSame(D3D11SurfaceRingBackend, windowsSurfaceBackend(redrawerClass(name)), name)
            assertFalse(isSkikoDirect3DRedrawer(redrawerClass(name)), name)
        }
        assertTrue(isSkikoDirect3DRedrawer(redrawerClass("Direct3DRedrawer")))
    }

    /**
     * The interop is read inside the Compose draw pass: for a redrawer without a D3D12
     * device it must report none (device 0 selects CPU frames) rather than throw, which
     * would stop the whole window from rendering.
     */
    @Test
    fun `DirectX interop reports no device for a non-Direct3D redrawer instead of throwing`() {
        assumeFalse("Requires an AWT display", GraphicsEnvironment.isHeadless())
        SwingUtilities.invokeAndWait {
            if (SkiaLayer::class.java.methods.any { it.name.startsWith("getFrameDriver\$") }) {
                // A real software layer (Skiko 0.153+ has no replaceable redrawer slot); any
                // non-Direct3D renderer class answers the question, so this runs on every OS.
                val layer = SkiaLayer(renderApi = GraphicsApi.SOFTWARE_COMPAT)
                val window = JFrame("Mediamp software interop test")
                try {
                    window.contentPane.add(layer)
                    window.setSize(160, 120)
                    window.isVisible = true
                    val interop = SkiaDirectXInterop(SkiaLayerRedrawer(layer))
                    assertEquals(0L, interop.renderDevicePtr)
                    assertNull(interop.directContext)
                } finally {
                    layer.dispose()
                    window.dispose()
                }
                return@invokeAndWait
            }
            val layer = SkiaLayer()
            val managerField = SkiaLayer::class.java.getDeclaredField("redrawerManager")
                .apply { isAccessible = true }
            val manager = managerField.get(layer)
            val redrawerField = manager.javaClass.getDeclaredField("redrawer")
                .apply { isAccessible = true }
            val original = redrawerField.get(manager)
            val redrawerType = Class.forName("org.jetbrains.skiko.redrawer.Redrawer")
            val softwareLike = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(redrawerType)) { _, _, _ -> null }
            val interop = SkiaDirectXInterop(SkiaLayerRedrawer(layer))
            try {
                redrawerField.set(manager, softwareLike)
                assertEquals(0L, interop.renderDevicePtr)
                assertNull(interop.directContext)
            } finally {
                redrawerField.set(manager, original)
                layer.dispose()
            }
        }
    }
}
