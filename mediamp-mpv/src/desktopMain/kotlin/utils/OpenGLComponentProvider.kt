/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.openani.mediamp.mpv.utils

import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.context.ContextHandler
import org.jetbrains.skiko.context.OpenGLContextHandler
import org.jetbrains.skiko.redrawer.LinuxOpenGLRedrawer
import org.jetbrains.skiko.redrawer.WindowsOpenGLRedrawer

class OpenGLComponentProvider(skiaLayer: SkiaLayer) {
    private val openglRedrawer: Any =
        when (val redrawer = requireNotNull(skiaLayer.redrawer)) {
            is WindowsOpenGLRedrawer -> redrawer as Any
            is LinuxOpenGLRedrawer -> redrawer as Any
            else -> error("Unsupported redrawer: ${redrawer::class.qualifiedName}")
        }

    private val deviceHandleField = runCatching {
        openglRedrawer.javaClass.getDeclaredField("device").also { it.isAccessible = true }
    }.getOrNull()

    private val glContextHandleField = openglRedrawer.javaClass
        .getDeclaredField("context")
        .also { it.isAccessible = true }

    private val contextHandlerHandleField = openglRedrawer.javaClass
        .getDeclaredField("contextHandler")
        .also { it.isAccessible = true }
    private val directContextHandler = ContextHandler::class.java
        .getDeclaredField("context")
        .also { it.isAccessible = true }

    val glDevice: Long get() = deviceHandleField?.getLong(openglRedrawer) ?: 0L
    val glContext: Long get() = glContextHandleField.getLong(openglRedrawer)

    val directContext: DirectContext
        get() = (contextHandlerHandleField.get(openglRedrawer) as OpenGLContextHandler)
            .let { directContextHandler.get(it) as DirectContext }
}
