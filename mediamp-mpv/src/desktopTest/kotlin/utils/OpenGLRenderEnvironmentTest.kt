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
    fun `environment identity tracks the GLX share group and drawable`() {
        val component = Canvas()
        val first = OpenGLRenderEnvironment(component, shareContext = 11, drawable = 22, window = 33)
        val same = OpenGLRenderEnvironment(component, shareContext = 11, drawable = 22, window = 33)
        val recreated = OpenGLRenderEnvironment(component, shareContext = 44, drawable = 22, window = 33)

        assertEquals(first.identity, same.identity)
        assertNotEquals(first.identity, recreated.identity)
    }

    @Test
    fun `environment rejects a redrawer that has not created a GLX context or drawable`() {
        val component = Canvas()

        assertFailsWith<IllegalArgumentException> {
            OpenGLRenderEnvironment(component, shareContext = 0, drawable = 1, window = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            OpenGLRenderEnvironment(component, shareContext = 1, drawable = 0, window = 1)
        }
    }
}
