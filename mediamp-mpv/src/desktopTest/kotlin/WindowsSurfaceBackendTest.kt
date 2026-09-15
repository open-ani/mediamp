/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import org.openani.mediamp.mpv.internal.D3D11SurfaceRingBackend
import org.openani.mediamp.mpv.internal.WindowsOpenGLSurfaceBackend
import org.openani.mediamp.mpv.internal.windowsSurfaceBackend
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WindowsSurfaceBackendTest {
    private fun redrawerClass(name: String): Class<*> =
        Class.forName("org.jetbrains.skiko.redrawer.$name", false, javaClass.classLoader)

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

    @Test
    fun `unsupported redrawers fail with the actual class name`() {
        val redrawer = redrawerClass("SoftwareRedrawer")
        val error = assertFailsWith<IllegalStateException> { windowsSurfaceBackend(redrawer) }
        assertTrue(error.message.orEmpty().contains(redrawer.name))
    }
}
