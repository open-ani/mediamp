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

/**
 * Reads the latest rendered frame as ARGB_8888 pixels (`0xAARRGGBB`, row-major,
 * top-down, alpha forced opaque), writing `[width, height]` into [dims] (size >= 2).
 * Returns `null` when no frame is available.
 */
@InternalMediampApi
external fun nReadSurfacePixelsMacos(ptr: Long, dims: IntArray): IntArray?

// Windows render path (render_d3d11.cpp): a native render thread drives mpv through
// the libmpv D3D11 render API into a ring of shared textures, each also opened on
// Skia's D3D12 device as an ID3D12Resource.

@InternalMediampApi
external fun nCreateRenderContextD3D11(ptr: Long): Boolean

@InternalMediampApi
external fun nDestroyRenderContextD3D11(ptr: Long): Boolean

/**
 * Asks the render thread to (re)allocate the buffer ring at [width] x [height], opening
 * each texture on the ID3D12Device inside [skikoDevicePtr] (a pointer to Skiko's native
 * DirectXDevice struct; 0 = D3D11-only ring without a Skia side, used headless).
 * Non-positive size deactivates the surface. Asynchronous — the swap happens between
 * frames.
 */
@InternalMediampApi
external fun nSetSurfaceConfigD3D11(ptr: Long, width: Int, height: Int, skikoDevicePtr: Long): Boolean

/**
 * Packed frame state: generation(16) | latestIndex(4, 0xF = none) | width(14) |
 * height(14) | serial(16). Any change means a new frame or a new buffer ring.
 */
@InternalMediampApi
external fun nGetFrameStateD3D11(ptr: Long): Long

/** ID3D12Resource pointer of ring buffer [index] for the current generation, or 0. */
@InternalMediampApi
external fun nGetBufferTextureD3D11(ptr: Long, index: Int): Long

/** Signals that the previous buffer generation is no longer referenced and may be freed. */
@InternalMediampApi
external fun nAckRetiredBuffersD3D11(ptr: Long): Boolean

@InternalMediampApi
external fun nHasD3D11Surface(ptr: Long): Boolean

/** Saves the latest rendered frame (shared texture contents) as PNG. */
@InternalMediampApi
external fun nSaveSurfacePngD3D11(ptr: Long, path: String): Boolean

/**
 * Reads the latest rendered frame as ARGB_8888 pixels (`0xAARRGGBB`, row-major,
 * top-down, alpha forced opaque), writing `[width, height]` into [dims] (size >= 2).
 * Returns `null` when no frame is available.
 */
@InternalMediampApi
external fun nReadSurfacePixelsD3D11(ptr: Long, dims: IntArray): IntArray?

@OptIn(InternalMediampApi::class)
internal actual fun attachSurface(ptr: Long, surface: Any): Boolean {
    error("only implemented on Android")
}

@OptIn(InternalMediampApi::class)
internal actual fun detachSurface(ptr: Long): Boolean {
    error("only implemented on Android")
}
