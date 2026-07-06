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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.LocalWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
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
 * macOS render path: a native render thread drives mpv (hwdec=videotoolbox, OpenGL over
 * an offscreen CGL context) into a triple-buffered IOSurface ring, which Skia samples
 * as MTLTextures on its own Metal device — the video becomes a regular draw call in the
 * Compose scene graph, zero-copy end to end, and this thread never renders or blocks.
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
    val canvasSize = remember { mutableStateOf(IntSize.Zero) }
    // The render context itself is created eagerly by the player (it must exist before
    // loadfile); this composable only owns the surface config and the frame listener.
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

    // Buffer-ring sizing: the first layout configures immediately; later size changes
    // (window resize, overlay-driven relayout) settle for 150ms first. The reallocation
    // itself happens between frames on the native render thread, and the draw pass
    // letterboxes whatever the ring currently contains, so resizes cost no visible
    // frames — the video keeps playing at the old size until the new ring has content.
    LaunchedEffect(player, interop) {
        val skiaInterop = interop ?: return@LaunchedEffect
        var configured = false
        snapshotFlow { canvasSize.value }
            .filter { it.width > 0 && it.height > 0 }
            .collectLatest { size ->
                if (configured) delay(150)
                while (true) {
                    val devicePtr = runCatching { skiaInterop.mtlDevicePtr }.getOrNull()
                    if (devicePtr != null &&
                        player.requestMacosSurface(size.width, size.height, devicePtr)
                    ) {
                        configured = true
                        break
                    }
                    delay(50) // Skiko redrawer not up yet; retry shortly
                }
            }
    }

    Canvas(modifier.onSizeChanged { canvasSize.value = it }) {
        frameTick.longValue // subscribe: redraw whenever mpv publishes a new frame

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
        // Skiko can recreate its redrawer (and MTLDevice) at runtime; the ring's
        // textures must live on Skia's current device.
        runCatching { skiaInterop.mtlDevicePtr }.getOrNull()?.let {
            player.refreshMacosDeviceIfChanged(it)
        }

        logOnce("rendering ${width}x${height} via Metal surface")
        // Draw through Compose so the op survives RenderNode display-list recording
        // (raw nativeCanvas draws are dropped there). The image is a Skia-owned texture:
        // snapshots of the BRT-wrapped surface itself do not render. The frame normally
        // matches the composable size; during a resize settle it is the old size, so fit
        // it preserving aspect (letterbox) instead of stretching.
        player.currentFrameImage(directContext)?.let { frame ->
            val scale = minOf(
                size.width / frame.width.toFloat(),
                size.height / frame.height.toFloat(),
            )
            val dstWidth = (frame.width * scale).toInt()
            val dstHeight = (frame.height * scale).toInt()
            drawImage(
                frame.toComposeImageBitmap(),
                srcSize = IntSize(frame.width, frame.height),
                dstOffset = IntOffset(
                    (width - dstWidth) / 2,
                    (height - dstHeight) / 2,
                ),
                dstSize = IntSize(dstWidth, dstHeight),
                filterQuality = FilterQuality.High,
            )
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
