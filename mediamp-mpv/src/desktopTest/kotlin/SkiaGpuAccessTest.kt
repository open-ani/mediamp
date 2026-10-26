/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkiaGpuAccessTest {
    private open class Renderer {
        val drawLock = Any()
    }

    private class InheritedRenderer : Renderer()

    @Test
    fun `players use the same inherited renderer monitor and run nullable actions once`() {
        val renderer = InheritedRenderer()
        var calls = 0
        repeat(2) {
            assertNull(SkiaGpuAccess(renderer).withLock {
                assertTrue(Thread.holdsLock(renderer.drawLock))
                calls++
                null
            })
        }
        assertEquals(2, calls)
    }

    @Test
    fun `installed asynchronous Skiko renderers expose the shared monitor`() {
        for (name in listOf(
            "redrawer.MetalRedrawer",
            "redrawer.Direct3DRedrawer",
            "renderer.MetalRenderer",
            "renderer.Direct3DRenderer",
        )) {
            val rendererClass = try {
                Class.forName("org.jetbrains.skiko.$name", false, javaClass.classLoader)
            } catch (_: ClassNotFoundException) {
                continue // Renderer names changed in Skiko 0.153.
            }
            val field = rendererClass.skiaFieldOrNull("drawLock")
            assertTrue(field != null, "$name must expose drawLock before GPU interop is safe")
            assertEquals(Any::class.java, field.type)
        }
    }

    @Test
    fun `legacy EDT renderers without a lock remain supported`() {
        assertEquals(42, SkiaGpuAccess(Any()).withLock { 42 })
    }
}
