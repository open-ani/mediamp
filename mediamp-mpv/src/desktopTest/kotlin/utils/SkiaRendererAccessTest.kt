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
import org.openani.mediamp.mpv.internal.D3D11SurfaceRingBackend
import org.openani.mediamp.mpv.internal.WindowsOpenGLSurfaceBackend
import org.openani.mediamp.mpv.internal.windowsSurfaceBackend
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SkiaRendererAccessTest {
    class LegacyLayer(var renderer: Any? = null) {
        fun `getRedrawer$skiko`(): Any? = renderer
    }

    class ModernLayer(var driver: Driver? = null) {
        fun `getFrameDriver$skiko`(): Driver? = driver
    }

    class Driver(private var current: Any?) {
        private val renderer: Any? get() = current
        fun replace(renderer: Any?) { current = renderer }
    }

    @Test
    fun `legacy accessor follows renderer replacement and removal`() {
        val layer = LegacyLayer()
        val access = SkiaRendererAccess(LegacyLayer::class.java)
        assertNull(access.currentRenderer(layer))
        repeat(2) {
            val renderer = Any()
            layer.renderer = renderer
            assertSame(renderer, access.currentRenderer(layer))
        }
        layer.renderer = null
        assertNull(access.currentRenderer(layer))
    }

    @Test
    fun `frame driver accessor follows both driver and renderer replacements`() {
        val layer = ModernLayer()
        val access = SkiaRendererAccess(ModernLayer::class.java)
        assertNull(access.currentRenderer(layer))
        val first = Any()
        layer.driver = Driver(first)
        assertSame(first, access.currentRenderer(layer))
        val second = Any()
        layer.driver!!.replace(second)
        assertSame(second, access.currentRenderer(layer))
        val third = Any()
        layer.driver = Driver(third)
        assertSame(third, access.currentRenderer(layer))
        layer.driver = null
        assertNull(access.currentRenderer(layer))
    }

    class Handler(var context: DirectContext? = null)
    class LegacyRenderer(private val contextHandler: Handler = Handler())
    class MetalRenderer(private val context: DirectContext? = null)
    open class OpenGLBase(private val glContext: DirectContext? = null)
    class OpenGLRenderer(private val context: Long = 1L) : OpenGLBase()

    @Test
    fun `context access supports handlers direct fields and inherited GL contexts`() {
        for (renderer in listOf(LegacyRenderer(), MetalRenderer(), OpenGLRenderer())) {
            assertNull(SkiaDirectContextAccess(renderer.javaClass).get(renderer))
        }
        assertFailsWith<IllegalStateException> { SkiaDirectContextAccess(Any::class.java) }
    }

    @Test
    fun `runtime renderer context layouts are supported on every desktop platform`() {
        val modern = SkiaLayer::class.java.methods.any { it.name == "getFrameDriver\$skiko" }
        val names = if (modern) {
            listOf("renderer.MetalRenderer", "renderer.Direct3DRenderer",
                "renderer.LinuxOpenGLRenderer", "renderer.WindowsOpenGLRenderer")
        } else {
            listOf("redrawer.MetalRedrawer", "redrawer.Direct3DRedrawer",
                "redrawer.LinuxOpenGLRedrawer", "redrawer.WindowsOpenGLRedrawer")
        }
        val classes = names.map { Class.forName("org.jetbrains.skiko.$it", false, javaClass.classLoader) }
        for (clazz in classes) {
            SkiaDirectContextAccess(clazz)
        }
        assertSame(D3D11SurfaceRingBackend, windowsSurfaceBackend(classes[1]))
        assertSame(WindowsOpenGLSurfaceBackend, windowsSurfaceBackend(classes[3]))
        assertSame(java.lang.Long.TYPE, classes[1].getDeclaredField("device").type)
        for (clazz in classes.drop(2)) {
            assertSame(java.lang.Long.TYPE, clazz.getDeclaredField("context").type)
        }
        val metal = classes.first()
        assertTrue(isSkikoMetalRedrawer(metal))
        metal.getDeclaredField("adapter").type.getMethod("getPtr")
    }

    @Test
    fun `live Skiko renderer can be resolved and its context read`() {
        SwingUtilities.invokeAndWait {
            // The default-argument constructor ABI differs between Skiko versions.
            val constructor = SkiaLayer::class.java.constructors.single {
                it.parameterTypes.any { type -> type.name == "org.jetbrains.skiko.FrameBuffering" } &&
                    it.parameterTypes.last().name == "kotlin.jvm.internal.DefaultConstructorMarker"
            }
            val args = constructor.parameterTypes.map<Class<*>, Any?> { type ->
                when (type) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    else -> null
                }
            }.toTypedArray()
            args[args.size - 2] = (1 shl (args.size - 2)) - 1
            val layer = constructor.newInstance(*args) as SkiaLayer
            val window = JFrame("Mediamp Skiko compatibility test")
            try {
                val access = SkiaLayerRedrawer(layer)
                assertNull(access.redrawerOrNull)
                window.contentPane.add(layer)
                window.setSize(160, 120)
                window.isVisible = true
                val renderer = access.redrawer
                assertTrue(renderer.javaClass.name.startsWith("org.jetbrains.skiko."))
                if (isSkikoMetalRedrawer(renderer.javaClass)) {
                    val interop = SkiaMetalInterop(access)
                    assertTrue(interop.renderDevicePtr != 0L)
                    interop.directContext
                }
                assertSame(renderer, access.redrawer)
            } finally {
                layer.dispose()
                window.dispose()
            }
        }
    }
}
