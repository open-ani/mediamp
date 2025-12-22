/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */
@file:JvmName("MPVHandleDesktop")

package org.openani.mediamp.mpv

import org.openani.mediamp.InternalMediampApi

@InternalMediampApi
external fun nCreateRenderContext(ptr: Long): Boolean

@InternalMediampApi
external fun nDestroyRenderContext(ptr: Long): Boolean

@InternalMediampApi
external fun nRenderFrame(ptr: Long, fbo: Int, width: Int, height: Int): Boolean

@InternalMediampApi
external fun nAttachWindowSurface(ptr: Long, hwnd: Long): Boolean

@InternalMediampApi
external fun nDetachWindowSurface(ptr: Long): Boolean

@OptIn(InternalMediampApi::class)
internal actual fun attachSurface(ptr: Long, surface: Any): Boolean {
    return when (surface) {
        is Long -> try {
            nAttachWindowSurface(ptr, surface)
        } catch (_: UnsatisfiedLinkError) {
            nCreateRenderContext(ptr)
        }

        else -> {
            // The OpenGL context must be current before calling.
            nCreateRenderContext(ptr)
        }
    }
}

@OptIn(InternalMediampApi::class)
internal actual fun detachSurface(ptr: Long): Boolean {
    val result = try {
        nDetachWindowSurface(ptr)
    } catch (_: UnsatisfiedLinkError) {
        false
    }
    return nDestroyRenderContext(ptr) || result
}

@OptIn(InternalMediampApi::class)
actual fun createRenderContext(ptr: Long): Boolean {
    return nCreateRenderContext(ptr)
}

@OptIn(InternalMediampApi::class)
actual fun destroyRenderContext(ptr: Long): Boolean {
    return nDestroyRenderContext(ptr)
}

@OptIn(InternalMediampApi::class)
actual fun renderFrame(ptr: Long, fbo: Int, width: Int, height: Int): Boolean {
    return nRenderFrame(ptr, fbo, width, height)
}