/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.utils

import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.hostOs

/**
 * One live Skiko OpenGL share group, as observed from a single draw pass. The producer
 * context is created inside this group, so the values must never be cached beyond the
 * pass that read them: a redrawer replacement is a device-generation change.
 */
internal interface OpenGLRenderEnvironment {
    /**
     * Stable only for the lifetime of this GL share group. Two environments with equal
     * identities describe the same producer prerequisites.
     */
    val identity: Any

    /**
     * The value reported to the surface ring as its consumer render device. OpenGL rings
     * ignore it natively (the producer joins a share group instead of allocating on a
     * device), so it only has to change whenever [identity] does.
     */
    val renderDeviceToken: Long

    /** Attaches this environment to the native handle [handlePtr] (see `render_opengl.cpp`). */
    fun attach(handlePtr: Long): Boolean
}

/** Values read from one live redrawer for use during a single draw. */
internal class OpenGLRenderSnapshot(
    val environment: OpenGLRenderEnvironment,
    val directContext: DirectContext?,
)

/**
 * Reflective access to Skiko's OpenGL redrawer. Skiko exposes neither its GL context nor
 * its DirectContext, so implementations probe the live redrawer on every access and cache
 * reflection metadata only by redrawer class. Do not cache returned contexts or
 * environments.
 */
internal interface SkiaOpenGLInterop : SkiaRenderDeviceInterop {
    /** GL and Skia values from the same redrawer, valid for the current draw only. */
    fun renderSnapshot(): OpenGLRenderSnapshot
}

/** The interop matching this host's Skiko OpenGL redrawer: WGL on Windows, GLX elsewhere. */
internal fun createSkiaOpenGLInterop(layer: SkiaLayer): SkiaOpenGLInterop = when (hostOs) {
    OS.Windows -> SkiaWglInterop(layer)
    else -> SkiaGlxInterop(layer)
}
