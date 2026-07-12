/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */
@file:JvmName("MPVHandleDesktop")

package org.openani.mediamp.mpv

import java.awt.Component
import org.openani.mediamp.InternalMediampApi

// Desktop surface-ring render paths. Each native render thread drives mpv into a ring
// of GPU textures that the platform-specific Kotlin backend wraps for Skia. The native
// implementation owns the producer context and textures; Skia only borrows consumer
// views of them.

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

// OpenGL render path (Linux/GLX): a native render thread
// render into shared GL_TEXTURE_2D objects. The `consumerEnvironmentPtr` is an opaque
// native description of Skiko's live GLX environment, not a producer FBO or a texture
// ID. It is supplied only after the Compose consumer has attached a live OpenGL context.
//
// These declarations are the Kotlin/native contract for the later native renderer. The
// native code resolves the matching Display through JAWT without retaining the component.

/**
 * Attaches the live Skiko GLX share context before creating libmpv's OpenGL render
 * context. [component] is Skiko's AWT HardwareLayer; native code uses JAWT only while
 * this call is active to obtain the matching X11 Display. It must not retain the Java
 * object. [shareContext], [drawable], and [window] are GLX/X11 identities observed from
 * the same live LinuxOpenGLRedrawer. A changed identity requires a fresh attachment.
 */
@InternalMediampApi
external fun nAttachRenderEnvironmentOpenGL(
    ptr: Long,
    component: Component,
    shareContext: Long,
    drawable: Long,
    window: Long,
): Boolean

@InternalMediampApi
external fun nCreateRenderContextOpenGL(ptr: Long): Boolean

@InternalMediampApi
external fun nDestroyRenderContextOpenGL(ptr: Long): Boolean

@InternalMediampApi
external fun nSetSurfaceConfigOpenGL(
    ptr: Long,
    width: Int,
    height: Int,
    consumerEnvironmentPtr: Long,
): Boolean

/** Packed frame state: generation(16) | latestIndex(4, 0xF = none) | width(14) | height(14) | serial(16). */
@InternalMediampApi
external fun nGetFrameStateOpenGL(ptr: Long): Long

/** Shared GL_TEXTURE_2D name of ring buffer [index], or 0 when no active ring exists. */
@InternalMediampApi
external fun nGetBufferTextureOpenGL(ptr: Long, index: Int): Long

/** Signals that Skia no longer references the retired shared-texture generation. */
@InternalMediampApi
external fun nAckRetiredBuffersOpenGL(ptr: Long): Boolean

@InternalMediampApi
external fun nHasOpenGLSurface(ptr: Long): Boolean

/** Saves the latest producer texture through native debug readback. */
@InternalMediampApi
external fun nSaveSurfacePngOpenGL(ptr: Long, path: String): Boolean

/**
 * Creates a consumer-context FBO and attaches [textureName]. This must be called only
 * while Skiko's GLX context is current. The producer owns the texture; Kotlin owns and
 * later deletes the returned FBO in the same consumer context.
 */
@InternalMediampApi
external fun nCreateOpenGLConsumerFbo(textureName: Long): Int

/** Deletes a consumer FBO previously returned by [nCreateOpenGLConsumerFbo]. */
@InternalMediampApi
external fun nDeleteOpenGLConsumerFbo(fbo: Int): Boolean
@OptIn(InternalMediampApi::class)
internal actual fun attachSurface(ptr: Long, surface: Any): Boolean {
    error("only implemented on Android")
}

@OptIn(InternalMediampApi::class)
internal actual fun detachSurface(ptr: Long): Boolean {
    error("only implemented on Android")
}
