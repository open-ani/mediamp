/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.utils

import java.awt.Canvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class OpenGLRenderEnvironmentTest {
    @Test
    fun `GLX environment identity tracks the share group and drawable`() {
        val component = Canvas()
        val first = GlxRenderEnvironment(component, shareContext = 11, drawable = 22, window = 33)
        val same = GlxRenderEnvironment(component, shareContext = 11, drawable = 22, window = 33)
        val recreated = GlxRenderEnvironment(component, shareContext = 44, drawable = 22, window = 33)

        assertEquals(first.identity, same.identity)
        assertNotEquals(first.identity, recreated.identity)
        assertEquals(11L, first.renderDeviceToken)
    }

    @Test
    fun `GLX environment rejects a redrawer that has not created a context or drawable`() {
        val component = Canvas()

        assertFailsWith<IllegalArgumentException> {
            GlxRenderEnvironment(component, shareContext = 0, drawable = 1, window = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            GlxRenderEnvironment(component, shareContext = 1, drawable = 0, window = 1)
        }
    }

    @Test
    fun `WGL environment identity tracks the live context and the redrawer token`() {
        val first = WglRenderEnvironment(deviceContext = 11, shareContext = 22, redrawerToken = 33)
        val same = WglRenderEnvironment(deviceContext = 11, shareContext = 22, redrawerToken = 33)
        // A recreated redrawer whose driver handed back the same HGLRC still counts as a
        // new share group, and vice versa.
        val newContext = WglRenderEnvironment(deviceContext = 11, shareContext = 44, redrawerToken = 33)
        val newRedrawer = WglRenderEnvironment(deviceContext = 11, shareContext = 22, redrawerToken = 55)

        assertEquals(first.identity, same.identity)
        assertNotEquals(first.identity, newContext.identity)
        assertNotEquals(first.identity, newRedrawer.identity)
        assertEquals(33L, first.renderDeviceToken)
    }

    @Test
    fun `WGL environment rejects a thread without a current context`() {
        assertFailsWith<IllegalArgumentException> {
            WglRenderEnvironment(deviceContext = 0, shareContext = 1, redrawerToken = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            WglRenderEnvironment(deviceContext = 1, shareContext = 0, redrawerToken = 1)
        }
    }
}
