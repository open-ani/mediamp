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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.LocalWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.MPVLog
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.utils.SkiaDirectXInterop
import org.openani.mediamp.mpv.utils.SkiaMetalInterop
import org.openani.mediamp.mpv.utils.SkiaRenderDeviceInterop
import org.openani.mediamp.mpv.utils.findSkiaLayer
import kotlin.time.Duration.Companion.milliseconds

@Composable
actual fun MpvMediampPlayerSurface(
    player: MpvMediampPlayer,
    modifier: Modifier,
) {
    when (hostOs) {
        OS.MacOS, OS.Windows -> MpvMediampPlayerSurfaceRing(player, modifier)
        else -> Box(modifier) // TODO: Linux render path
    }
}

/**
 * Shared surface-ring render path (macOS Metal + Windows D3D11):
 *
 * A native render thread drives mpv into a triple-buffered ring of GPU textures that
 * are simultaneously visible to Skia's own render device — IOSurfaces wrapped as
 * MTLTextures on macOS (hwdec=videotoolbox, OpenGL over an offscreen CGL context), NT
 * shared handles opened as ID3D12Resources on Windows (hwdec=d3d11va, libmpv D3D11
 * render API). The video becomes a regular draw call in the Compose scene graph, zero
 * extra CPU copies end to end, and this thread never renders or blocks.
 */
@OptIn(InternalMediampApi::class)
@Composable
private fun MpvMediampPlayerSurfaceRing(
    player: MpvMediampPlayer,
    modifier: Modifier,
) {
    val window = LocalWindow.current
    val interop: SkiaRenderDeviceInterop? = remember(window) {
        if (window == null) {
            MPVLog.warn(player.handle.ptr, "LocalWindow.current is null; cannot locate SkiaLayer, video stays black")
            return@remember null
        }
        val layer = window.findSkiaLayer()
        if (layer == null) {
            MPVLog.warn(player.handle.ptr, "no SkiaLayer found in window $window; video stays black")
            return@remember null
        }
        runCatching {
            when (hostOs) {
                OS.MacOS -> SkiaMetalInterop(layer)
                OS.Windows -> SkiaDirectXInterop(layer)
                else -> null
            }
        }
            .onFailure { MPVLog.error(player.handle.ptr, "Skia device interop init failed; video stays black", it) }
            .getOrNull()
    }
    val frameTick = remember { mutableLongStateOf(0L) }
    val canvasSize = remember { mutableStateOf(IntSize.Zero) }
    // The render context itself is created eagerly by the player (it must exist before
    // loadfile); this composable only owns the surface config and the frame listener.
    var renderContextReady by remember(player) { mutableStateOf(false) }
    val loggedStates = remember(player) { mutableSetOf<String>() }
    fun logOnce(state: String, level: Int = MPVLog.DEBUG) {
        if (loggedStates.add(state)) MPVLog.log(player.handle.ptr, level, state)
    }

    DisposableEffect(player) {
        renderContextReady = player.createRenderContext() // no-op if already created
        if (renderContextReady) {
            player.setRenderUpdateListener { frameTick.longValue++ }
        }
        onDispose {
            player.setRenderUpdateListener(null)
            player.releaseSurface()
            renderContextReady = false
        }
    }

    // Buffer-ring sizing: the first layout configures immediately; later size changes
    // (window resize, overlay-driven relayout) settle for 150ms first. The reallocation
    // itself happens between frames on the native render thread, and the draw pass
    // letterboxes whatever the ring currently contains, so resizes cost no visible
    // frames — the video keeps playing at the old size until the new ring has content.
    LaunchedEffect(player, interop) {
        val deviceInterop = interop ?: return@LaunchedEffect
        var configured = false
        snapshotFlow { canvasSize.value }
            .filter { it.width > 0 && it.height > 0 }
            .collectLatest { size ->
                if (configured) delay(150.milliseconds)
                while (true) {
                    val devicePtr = runCatching { deviceInterop.renderDevicePtr }.getOrNull()
                    if (devicePtr != null &&
                        player.requestSurface(size.width, size.height, devicePtr)
                    ) {
                        configured = true
                        break
                    }
                    delay(50.milliseconds) // Skiko redrawer not up yet; retry shortly
                }
            }
    }

    Canvas(modifier.onSizeChanged { canvasSize.value = it }) {
        frameTick.longValue // subscribe: redraw whenever mpv publishes a new frame

        if (!renderContextReady) {
            logOnce("render context not ready")
            return@Canvas
        }
        if (interop == null) {
            logOnce("skia interop unavailable; video stays black (frames are drained)", MPVLog.ERROR)
            return@Canvas
        }
        val directContext = interop.directContext
        if (directContext == null) {
            logOnce("DirectContext not initialized yet")
            return@Canvas
        }
        val width = size.width.toInt()
        val height = size.height.toInt()
        if (width <= 0 || height <= 0) return@Canvas
        // Skiko can recreate its redrawer (and render device) at runtime; the ring's
        // textures must live on Skia's current device.
        runCatching { interop.renderDevicePtr }.getOrNull()?.let {
            player.refreshDeviceIfChanged(it)
        }

        logOnce("rendering ${width}x${height} via ${if (hostOs == OS.MacOS) "Metal" else "D3D12"} surface", MPVLog.INFO)
        // Draw through Compose so the op survives RenderNode display-list recording
        // (raw nativeCanvas draws are dropped there). The image is a Skia-owned texture:
        // snapshots of the BRT-wrapped surface itself do not render. The frame normally
        // matches the composable size; during a resize settle it is the old size, so fit
        // it preserving aspect (letterbox) instead of stretching.
        // Draw the GPU-backed frame image straight onto the Compose canvas via the Skia
        // nativeCanvas — a zero-copy GPU->GPU draw on the current DirectContext. This
        // deliberately does NOT go through toComposeImageBitmap()/drawImage(ImageBitmap):
        // that reads the GPU image back to a CPU bitmap every frame, which stalls the
        // whole Compose scene (~20ms at 4K, dragging any overlay such as danmaku down to
        // ~40fps) and crashes on resize. The frame normally matches the composable size;
        // during a resize settle it is the old size, so fit it preserving aspect
        // (letterbox) instead of stretching.
        player.currentFrameImage(directContext)?.let { frame ->
            val scale = minOf(
                size.width / frame.width.toFloat(),
                size.height / frame.height.toFloat(),
            )
            val dstWidth = frame.width * scale
            val dstHeight = frame.height * scale
            val dx = (size.width - dstWidth) / 2f
            val dy = (size.height - dstHeight) / 2f
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawImageRect(
                    image = frame,
                    src = Rect.makeWH(frame.width.toFloat(), frame.height.toFloat()),
                    dst = Rect.makeXYWH(dx, dy, dstWidth, dstHeight),
                    samplingMode = SamplingMode.LINEAR,
                    paint = null,
                    strict = true,
                )
            }
        }
    }
}
