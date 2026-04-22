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
import org.jetbrains.skia.GLBackendState
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

    fun resetContextGLAfterMpvRender() {
        // libmpv's OpenGL backend dirties modern GL state such as FBO/program/texture/view/blend/pixel-store.
        // We only invalidate the state groups Skia can cache across those external calls.
        directContext.resetGL(
            GLBackendState.RENDER_TARGET,
            GLBackendState.TEXTURE_BINDING,
            GLBackendState.VIEW,
            GLBackendState.BLEND,
            GLBackendState.VERTEX,
            GLBackendState.PIXEL_STORE,
            GLBackendState.PROGRAM,
            GLBackendState.MISC,
        )
    }

    fun resetContextGLAfterTextureRecreate() {
        directContext.resetGL(
            GLBackendState.RENDER_TARGET,
            GLBackendState.TEXTURE_BINDING,
            GLBackendState.PIXEL_STORE,
            GLBackendState.MISC,
        )
    }
}