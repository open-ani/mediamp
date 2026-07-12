/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlin.test.Test
import kotlin.test.assertTrue

class SkikoLinuxOpenGLReflectionLayoutTest {
    @Test
    fun `Skiko Linux OpenGL reflection layout matches the production provider`() {
        val result = probeSkikoLinuxOpenGLReflectionLayout()
        System.err.println("[LinuxOpenGLValidation] Skiko reflection preflight: ${result.message}")
        assertTrue(result.compatible, "Skiko 0.9.37.4 reflection layout changed: ${result.message}")
    }
}

private data class ReflectionLayoutResult(val compatible: Boolean, val message: String)

/** Checks the private Skiko layout without initializing SkiaLayer or opening a GLX context. */
private fun probeSkikoLinuxOpenGLReflectionLayout(): ReflectionLayoutResult {
    val classLoader = SkikoLinuxOpenGLReflectionLayoutTest::class.java.classLoader
    val layer = runCatching { Class.forName("org.jetbrains.skiko.SkiaLayer", false, classLoader) }
        .getOrElse { return ReflectionLayoutResult(false, "SkiaLayer is not on the classpath: $it") }
    val redrawer = runCatching {
        Class.forName("org.jetbrains.skiko.redrawer.LinuxOpenGLRedrawer", false, classLoader)
    }.getOrElse { return ReflectionLayoutResult(false, "LinuxOpenGLRedrawer is not on the classpath: $it") }
    val redrawerGetter = layer.methods.firstOrNull { it.name == "getRedrawer\$skiko" && it.parameterCount == 0 }
        ?: return ReflectionLayoutResult(false, "SkiaLayer.getRedrawer\$skiko() is missing")
    val contextHandler = generateSequence(redrawer) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .firstOrNull { it.name == "contextHandler" }
        ?: return ReflectionLayoutResult(false, "LinuxOpenGLRedrawer.contextHandler is missing")
    val context = generateSequence(contextHandler.type) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .firstOrNull { it.name == "context" }
        ?: return ReflectionLayoutResult(false, "${contextHandler.type.name}.context is missing")

    return ReflectionLayoutResult(
        compatible = true,
        message = "${redrawer.name}; ${redrawerGetter.name}; ${contextHandler.type.name}.${context.name}",
    )
}
