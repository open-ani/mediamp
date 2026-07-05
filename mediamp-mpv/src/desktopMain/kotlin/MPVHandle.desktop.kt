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

// macOS render path (render_macos.mm): mpv → IOSurface-backed FBO → MTLTexture for Skia.

@InternalMediampApi
external fun nCreateRenderContextMacos(ptr: Long): Boolean

@InternalMediampApi
external fun nDestroyRenderContextMacos(ptr: Long): Boolean

/** Returns a retained MTLTexture pointer backed by the IOSurface mpv renders into, or 0. */
@InternalMediampApi
external fun nCreateMetalSurface(ptr: Long, width: Int, height: Int, mtlDevicePtr: Long): Long

@InternalMediampApi
external fun nReleaseMetalSurface(ptr: Long): Boolean

@InternalMediampApi
external fun nRenderFrameMacos(ptr: Long): Boolean

@InternalMediampApi
external fun nHasMetalSurface(ptr: Long): Boolean

/** Saves the current IOSurface contents (the last rendered frame) as PNG. */
@InternalMediampApi
external fun nSaveSurfacePng(ptr: Long, path: String): Boolean

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