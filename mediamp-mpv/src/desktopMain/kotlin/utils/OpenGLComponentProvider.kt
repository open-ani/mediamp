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
import org.jetbrains.skiko.redrawer.WindowsOpenGLRedrawer

class OpenGLComponentProvider(skiaLayer: SkiaLayer) {
    private val openglRedrawer: WindowsOpenGLRedrawer = skiaLayer.redrawer as WindowsOpenGLRedrawer

    private val deviceHandleField = WindowsOpenGLRedrawer::class.java
        .getDeclaredField("device")
        .also { it.isAccessible = true }

    private val glContextHandleField = WindowsOpenGLRedrawer::class.java
        .getDeclaredField("context")
        .also { it.isAccessible = true }

    private val contextHandlerHandleField = WindowsOpenGLRedrawer::class.java
        .getDeclaredField("contextHandler")
        .also { it.isAccessible = true }
    private val directContextHandler = ContextHandler::class.java
        .getDeclaredField("context")
        .also { it.isAccessible = true }

    val glDevice: Long get() = deviceHandleField.getLong(openglRedrawer)
    val glContext: Long get() = glContextHandleField.getLong(openglRedrawer)

    val directContext: DirectContext
        get() = (contextHandlerHandleField.get(openglRedrawer) as OpenGLContextHandler)
            .let { directContextHandler.get(it) as DirectContext }
}