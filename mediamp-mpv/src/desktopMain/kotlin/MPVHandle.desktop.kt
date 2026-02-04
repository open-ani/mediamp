/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */
@file:JvmName("MPVHandleDesktop")

package org.openani.mediamp.mpv

import org.openani.mediamp.InternalMediampApi

@InternalMediampApi
external fun nCreateRenderContext(ptr: Long, devicePtr: Long, contextPtr: Long): Boolean

@InternalMediampApi
external fun nDestroyRenderContext(ptr: Long): Boolean

@InternalMediampApi
external fun nCreateTexture(ptr: Long, width: Int, height: Int): Int

@InternalMediampApi
external fun nReleaseTexture(ptr: Long): Boolean

@InternalMediampApi
external fun nRenderFrameToTexture(ptr: Long): Boolean

@OptIn(InternalMediampApi::class)
internal actual fun attachSurface(ptr: Long, surface: Any): Boolean {
    error("only implemented on Android")
}

@OptIn(InternalMediampApi::class)
internal actual fun detachSurface(ptr: Long): Boolean {
    error("only implemented on Android")
}

@OptIn(InternalMediampApi::class)
actual fun createRenderContext(ptr: Long, devicePtr: Long, contextPtr: Long): Boolean {
    return nCreateRenderContext(ptr, devicePtr, contextPtr)
}

@OptIn(InternalMediampApi::class)
actual fun destroyRenderContext(ptr: Long): Boolean {
    return nDestroyRenderContext(ptr)
}

@OptIn(InternalMediampApi::class)
actual fun createTexture(ptr: Long, width: Int, height: Int): Int {
    return nCreateTexture(ptr, width, height)
}

@OptIn(InternalMediampApi::class)
actual fun releaseTexture(ptr: Long): Boolean {
    return nReleaseTexture(ptr)
}

@OptIn(InternalMediampApi::class)
actual fun renderFrameToTexture(ptr: Long): Boolean {
    return nRenderFrameToTexture(ptr)
}