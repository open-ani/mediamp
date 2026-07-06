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

// macOS render path (render_macos.mm): a native render thread drives mpv into a ring
// of IOSurface-backed FBOs, each also wrapped as an MTLTexture for Skia.

@InternalMediampApi
external fun nCreateRenderContextMacos(ptr: Long): Boolean

@InternalMediampApi
external fun nDestroyRenderContextMacos(ptr: Long): Boolean

/**
 * Asks the render thread to (re)allocate the buffer ring at [width] x [height] with
 * MTLTextures on [mtlDevicePtr] (0 = system default device). Non-positive size
 * deactivates the surface. Asynchronous — the swap happens between frames.
 */
@InternalMediampApi
external fun nSetSurfaceConfigMacos(ptr: Long, width: Int, height: Int, mtlDevicePtr: Long): Boolean

/**
 * Packed frame state: generation(16) | latestIndex(4, 0xF = none) | width(14) |
 * height(14) | serial(16). Any change means a new frame or a new buffer ring.
 */
@InternalMediampApi
external fun nGetFrameStateMacos(ptr: Long): Long

/** Retained MTLTexture pointer of ring buffer [index] for the current generation, or 0. */
@InternalMediampApi
external fun nGetBufferTextureMacos(ptr: Long, index: Int): Long

/** Signals that the previous buffer generation is no longer referenced and may be freed. */
@InternalMediampApi
external fun nAckRetiredBuffersMacos(ptr: Long): Boolean

@InternalMediampApi
external fun nHasMetalSurface(ptr: Long): Boolean

/** Saves the latest rendered frame (IOSurface contents) as PNG. */
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