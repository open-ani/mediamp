/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.openani.mediamp.mpv.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.LocalWindow
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.RenderUpdateListener
import org.openani.mediamp.mpv.utils.OpenGLComponentProvider
import org.openani.mediamp.mpv.utils.SkiaMetalInterop
import org.openani.mediamp.mpv.utils.findSkiaLayer

@Composable
actual fun MpvMediampPlayerSurface(
    player: MpvMediampPlayer,
    modifier: Modifier,
) {
    when (hostOs) {
        OS.MacOS -> MpvMediampPlayerSurfaceMacos(player, modifier)
        OS.Windows -> MpvMediampPlayerSurfaceWindows(player, modifier)
        else -> Box(modifier) // TODO: Linux render path
    }
}

/**
 * macOS render path: mpv draws (hwdec=videotoolbox, OpenGL over an offscreen CGL context)
 * into an IOSurface, which Skia samples as an MTLTexture on its own Metal device — the
 * video becomes a regular draw call in the Compose scene graph, zero-copy end to end.
 */
@OptIn(InternalMediampApi::class)
@Composable
private fun MpvMediampPlayerSurfaceMacos(
    player: MpvMediampPlayer,
    modifier: Modifier,
) {
    val window = LocalWindow.current
    val interop = remember(window) {
        if (window == null) {
            log("LocalWindow.current is null; cannot locate SkiaLayer")
            return@remember null
        }
        val layer = window.findSkiaLayer()
        if (layer == null) {
            log("no SkiaLayer found in window $window")
            return@remember null
        }
        runCatching { SkiaMetalInterop(layer) }
            .onFailure { log("SkiaMetalInterop failed: $it") }
            .getOrNull()
    }
    val frameTick = remember { mutableLongStateOf(0L) }
    // The render context itself is created eagerly by the player (it must exist before
    // loadfile); this composable only owns the IOSurface chain and the frame listener.
    var renderContextReady by remember(player) { mutableStateOf(false) }
    val loggedStates = remember(player) { mutableSetOf<String>() }
    fun logOnce(state: String) {
        if (loggedStates.add(state)) log(state)
    }

    DisposableEffect(player) {
        renderContextReady = player.createMacosRenderContext() // no-op if already created
        if (renderContextReady) {
            player.setRenderUpdateListener(
                object : RenderUpdateListener {
                    override fun onRenderUpdate() {
                        frameTick.longValue++
                    }
                },
            )
        }
        onDispose {
            player.setRenderUpdateListener(null)
            player.releaseMacosSurface()
            renderContextReady = false
        }
    }

    Canvas(modifier) {
        frameTick.longValue // subscribe: redraw whenever mpv reports a new frame

        if (!renderContextReady) {
            logOnce("render context not ready")
            return@Canvas
        }
        val skiaInterop = interop
        if (skiaInterop == null) {
            logOnce("skia interop unavailable; video stays black (frames are drained)")
            return@Canvas
        }
        val directContext = skiaInterop.directContext
        if (directContext == null) {
            logOnce("DirectContext not initialized yet")
            return@Canvas
        }
        val width = size.width.toInt()
        val height = size.height.toInt()
        if (width <= 0 || height <= 0) return@Canvas

        if (!player.ensureMacosSurface(width, height, skiaInterop.mtlDevicePtr, directContext)) {
            logOnce("ensureMacosSurface failed for ${width}x${height}")
            return@Canvas
        }
        logOnce("rendering ${width}x${height} via Metal surface")
        // Draw through Compose so the op survives RenderNode display-list recording
        // (raw nativeCanvas draws are dropped there). The image is a Skia-owned texture:
        // snapshots of the BRT-wrapped surface itself do not render.
        player.makeFrameImage()?.let { frame ->
            try {
                drawImage(frame.toComposeImageBitmap())
            } finally {
                frame.close()
            }
        }
    }
}

private fun log(message: String) {
    println("[MpvMediampPlayerSurface] $message")
}




/**
 * Windows render path: mpv renders into a GL texture created on Skia's own OpenGL
 * context (shared via wglShareLists in native code), adopted as a Skia [Image].
 */
@OptIn(InternalMediampApi::class)
@Composable
private fun MpvMediampPlayerSurfaceWindows(
    player: MpvMediampPlayer,
    modifier: Modifier,
) {
    val window = LocalWindow.current
    val components = remember(window) {
        window?.findSkiaLayer()?.let { layer ->
            runCatching { OpenGLComponentProvider(layer) }
                .onFailure { it.printStackTrace() }
                .getOrNull()
        }
    }

    var textureId by remember { mutableIntStateOf(0) }
    var renderContextInitialized by remember { mutableStateOf(false) }
    val interpolator = remember { FrameInterpolator() }

    DisposableEffect(components) {
        if (components == null) return@DisposableEffect onDispose { }

        renderContextInitialized = player.createRenderContext(components.glDevice, components.glContext)
        if (renderContextInitialized) {
            player.setRenderUpdateListener(interpolator)
        }

        onDispose {
            player.setRenderUpdateListener(null)
            player.releaseSkiaTextureAndImage()
            player.releaseTexture()
            player.releaseRenderContext()
            textureId = 0
            renderContextInitialized = false
        }
    }

    Canvas(modifier) {
        interpolator.updateSubscription

        if (!renderContextInitialized || components == null) return@Canvas
        val skiaCanvas = drawContext.canvas.nativeCanvas

        if (player.currentSize == null || player.currentSize != size || textureId == 0) {
            player.releaseSkiaTextureAndImage()
            player.releaseTexture()

            textureId = player.createTexture(size.width.toInt(), size.height.toInt())
            components.resetContextGLAfterTextureRecreate()

            if (textureId != 0) {
                val backendTexture = BackendTexture.makeGL(
                    width = size.width.toInt(),
                    height = size.height.toInt(),
                    isMipmapped = false,
                    textureId = textureId,
                    textureTarget = MpvMediampPlayer.GL_TEXTURE_2D,
                    textureFormat = MpvMediampPlayer.GL_RGBA8,
                ).also { player.backendTexture = it }

                player.image = Image.adoptTextureFrom(
                    context = components.directContext,
                    backendTexture = backendTexture,
                    origin = SurfaceOrigin.TOP_LEFT,
                    colorType = ColorType.RGBA_8888,
                )
            }

            player.currentSize = size
        }

        if (textureId != 0) {
            player.renderFrame()
            components.resetContextGLAfterMpvRender()
        }
        player.image?.let {
            skiaCanvas.drawImage(it, 0f, 0f)
        }
    }
}
