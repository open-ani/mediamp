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

class SkikoWindowsOpenGLReflectionLayoutTest {
    @Test
    fun `Skiko Windows OpenGL reflection layout matches the production interop`() {
        val result = probeSkikoWindowsOpenGLReflectionLayout()
        System.err.println("[WindowsOpenGLValidation] Skiko reflection preflight: ${result.message}")
        assertTrue(result.compatible, "Skiko 0.9.37.4 reflection layout changed: ${result.message}")
    }
}

private data class WindowsReflectionLayoutResult(val compatible: Boolean, val message: String)

/**
 * Checks the private Skiko layout SkiaWindowsOpenGLInterop reflects, without
 * initializing SkiaLayer or creating a WGL context — so it runs on every host OS.
 */
private fun probeSkikoWindowsOpenGLReflectionLayout(): WindowsReflectionLayoutResult {
    val classLoader = SkikoWindowsOpenGLReflectionLayoutTest::class.java.classLoader
    val layer = runCatching { Class.forName("org.jetbrains.skiko.SkiaLayer", false, classLoader) }
        .getOrElse { return WindowsReflectionLayoutResult(false, "SkiaLayer is not on the classpath: $it") }
    val redrawer = runCatching {
        Class.forName("org.jetbrains.skiko.redrawer.WindowsOpenGLRedrawer", false, classLoader)
    }.getOrElse { return WindowsReflectionLayoutResult(false, "WindowsOpenGLRedrawer is not on the classpath: $it") }
    val redrawerGetter = layer.methods.firstOrNull { it.name == "getRedrawer\$skiko" && it.parameterCount == 0 }
        ?: return WindowsReflectionLayoutResult(false, "SkiaLayer.getRedrawer\$skiko() is missing")
    val contextHandler = generateSequence(redrawer) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .firstOrNull { it.name == "contextHandler" }
        ?: return WindowsReflectionLayoutResult(false, "WindowsOpenGLRedrawer.contextHandler is missing")
    val redrawerContext = generateSequence(redrawer) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .firstOrNull { it.name == "context" && it.type == java.lang.Long.TYPE }
        ?: return WindowsReflectionLayoutResult(false, "WindowsOpenGLRedrawer.context (long) is missing")
    val context = generateSequence(contextHandler.type) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .firstOrNull { it.name == "context" }
        ?: return WindowsReflectionLayoutResult(false, "${contextHandler.type.name}.context is missing")

    return WindowsReflectionLayoutResult(
        compatible = true,
        message = "${redrawer.name}; ${redrawerGetter.name}; ${contextHandler.type.name}.${context.name}; " +
            "${redrawer.simpleName}.${redrawerContext.name}",
    )
}
