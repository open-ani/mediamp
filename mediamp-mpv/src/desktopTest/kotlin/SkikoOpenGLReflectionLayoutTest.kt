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

/**
 * Both OpenGL redrawers ship in the platform-independent `skiko-awt` jar, so each host
 * can preflight the private layout the production interops reflect
 * (`SkiaGlxInterop`, `SkiaWglInterop`).
 */
class SkikoOpenGLReflectionLayoutTest {
    @Test
    fun `Skiko Linux OpenGL reflection layout matches the production provider`() {
        assertReflectionLayout("org.jetbrains.skiko.redrawer.LinuxOpenGLRedrawer")
    }

    @Test
    fun `Skiko Windows OpenGL reflection layout matches the production provider`() {
        // The Windows interop only reflects `contextHandler.context` (Skia's
        // DirectContext) and `context` as an opaque identity token; the WGL handles
        // themselves come from wglGetCurrentDC/wglGetCurrentContext natively.
        assertReflectionLayout("org.jetbrains.skiko.redrawer.WindowsOpenGLRedrawer")
    }

    private fun assertReflectionLayout(redrawerClassName: String) {
        val result = probeSkikoOpenGLReflectionLayout(redrawerClassName)
        System.err.println("[OpenGLValidation] Skiko reflection preflight: ${result.message}")
        assertTrue(result.compatible, "Skiko 0.9.37.4 reflection layout changed: ${result.message}")
    }
}

private data class ReflectionLayoutResult(val compatible: Boolean, val message: String)

/** Checks the private Skiko layout without initializing SkiaLayer or opening a GL context. */
private fun probeSkikoOpenGLReflectionLayout(redrawerClassName: String): ReflectionLayoutResult {
    val classLoader = SkikoOpenGLReflectionLayoutTest::class.java.classLoader
    val layer = runCatching { Class.forName("org.jetbrains.skiko.SkiaLayer", false, classLoader) }
        .getOrElse { return ReflectionLayoutResult(false, "SkiaLayer is not on the classpath: $it") }
    val redrawer = runCatching { Class.forName(redrawerClassName, false, classLoader) }
        .getOrElse { return ReflectionLayoutResult(false, "$redrawerClassName is not on the classpath: $it") }
    val redrawerGetter = layer.methods.firstOrNull { it.name == "getRedrawer\$skiko" && it.parameterCount == 0 }
        ?: return ReflectionLayoutResult(false, "SkiaLayer.getRedrawer\$skiko() is missing")
    val declaredFields = generateSequence(redrawer) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .toList()
    val contextHandler = declaredFields.firstOrNull { it.name == "contextHandler" }
        ?: return ReflectionLayoutResult(false, "$redrawerClassName.contextHandler is missing")
    val glContext = declaredFields.firstOrNull { it.name == "context" }
        ?: return ReflectionLayoutResult(false, "$redrawerClassName.context is missing")
    if (glContext.type != java.lang.Long.TYPE) {
        return ReflectionLayoutResult(false, "$redrawerClassName.context is ${glContext.type}, expected long")
    }
    val context = generateSequence(contextHandler.type) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .firstOrNull { it.name == "context" }
        ?: return ReflectionLayoutResult(false, "${contextHandler.type.name}.context is missing")

    return ReflectionLayoutResult(
        compatible = true,
        message = "${redrawer.name}; ${redrawerGetter.name}; ${contextHandler.type.name}.${context.name}",
    )
}
