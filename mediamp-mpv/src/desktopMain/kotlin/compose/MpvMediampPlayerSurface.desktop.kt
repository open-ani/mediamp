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
import kotlinx.coroutines.flow.first
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.MPVLog
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.utils.OpenGLRenderEnvironment
import org.openani.mediamp.mpv.utils.OpenGLRenderSnapshot
import org.openani.mediamp.mpv.utils.SkiaDirectXInterop
import org.openani.mediamp.mpv.utils.SkiaMetalInterop
import org.openani.mediamp.mpv.utils.SkiaOpenGLInterop
import org.openani.mediamp.mpv.utils.SkiaRenderDeviceInterop
import org.openani.mediamp.mpv.utils.findSkiaLayer
import kotlin.time.Duration.Companion.milliseconds

@Composable
actual fun MpvMediampPlayerSurface(
    player: MpvMediampPlayer,
    modifier: Modifier,
) {
    when (hostOs) {
        OS.MacOS, OS.Windows, OS.Linux -> MpvMediampPlayerSurfaceRing(player, modifier)
        else -> Box(modifier)
    }
}

/**
 * Shared surface-ring render path (macOS Metal, Windows D3D11, Linux OpenGL/GLX):
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
            MPVLog.warn("LocalWindow.current is null; cannot locate SkiaLayer, video stays black")
            return@remember null
        }
        val layer = window.findSkiaLayer()
        if (layer == null) {
            MPVLog.warn("no SkiaLayer found in window $window; video stays black")
            return@remember null
        }
        runCatching {
            when (hostOs) {
                OS.MacOS -> SkiaMetalInterop(layer)
                OS.Windows -> SkiaDirectXInterop(layer)
                OS.Linux -> SkiaOpenGLInterop(layer)
                else -> null
            }
        }
            .onFailure { MPVLog.error("Skia device interop init failed; video stays black", it) }
            .getOrNull()
    }
    val frameTick = remember { mutableLongStateOf(0L) }
    val canvasSize = remember { mutableStateOf(IntSize.Zero) }
    // macOS/Windows create their producer context eagerly. Linux intentionally waits for
    // the live LinuxOpenGLRedrawer so `vo=libmpv` never sees loadfile before GLX sharing
    // has been attached.
    var renderContextReady by remember(player) { mutableStateOf(false) }
    val loggedStates = remember(player) { mutableSetOf<String>() }
    fun logOnce(state: String, level: Int = MPVLog.DEBUG) {
        if (loggedStates.add(state)) MPVLog.log(level, state)
    }
    // Linux re-checks the live GLX identity because Skiko may replace its redrawer.
    fun ensureRenderContext(environment: OpenGLRenderEnvironment? = null): Boolean {
        if (hostOs != OS.Linux) return renderContextReady
        environment ?: return false
        if (renderContextReady &&
            player.currentOpenGLRenderEnvironment()?.identity == environment.identity
        ) {
            return true
        }
        val ready = runCatching {
            player.attachOpenGLRenderEnvironment(environment)
        }.onFailure {
            MPVLog.error("Linux GLX render context unavailable; video stays black", it)
        }.getOrDefault(false)
        if (ready && !renderContextReady) {
            player.setRenderUpdateListener { frameTick.longValue++ }
            renderContextReady = true
        }
        return ready
    }

    fun currentOpenGLSnapshot(): OpenGLRenderSnapshot? {
        if (hostOs != OS.Linux) return null
        val glInterop = interop as? SkiaOpenGLInterop ?: return null
        val snapshot = runCatching { glInterop.renderSnapshot() }
            .onFailure { MPVLog.error("Linux GLX render context unavailable; video stays black", it) }
            .getOrNull() ?: return null
        return snapshot.takeIf { ensureRenderContext(it.environment) }
    }

    DisposableEffect(player) {
        // Linux creates its shared context later, from the live redrawer in Canvas.
        renderContextReady = hostOs != OS.Linux && player.createRenderContext()
        if (renderContextReady) {
            player.setRenderUpdateListener { frameTick.longValue++ }
        }
        onDispose {
            player.setRenderUpdateListener(null)
            player.releaseSurface()
            renderContextReady = false
        }
    }

    // Linux waits for GLX attachment; Metal/D3D keep their existing readiness retry.
    // The first ready layout configures immediately; later size changes
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
                if (hostOs == OS.Linux) {
                    snapshotFlow { renderContextReady }.first { it }
                }
                if (configured) delay(150.milliseconds)
                while (true) {
                    val devicePtr = runCatching { deviceInterop.renderDevicePtr }.getOrNull()
                    if (devicePtr != null &&
                        player.requestSurface(size.width, size.height, devicePtr)
                    ) {
                        configured = true
                        break
                    }
                    if (hostOs == OS.Linux) break
                    delay(50.milliseconds)
                }
            }
    }

    Canvas(modifier.onSizeChanged { canvasSize.value = it }) {
        frameTick.longValue // subscribe: redraw whenever mpv publishes a new frame

        if (interop == null) {
            logOnce("skia interop unavailable; video stays black (frames are drained)", MPVLog.ERROR)
            return@Canvas
        }
        val openGLSnapshot = currentOpenGLSnapshot()
        val renderReady = if (hostOs == OS.Linux) {
            openGLSnapshot != null
        } else {
            renderContextReady
        }
        if (!renderReady) {
            logOnce("render context not ready")
            return@Canvas
        }
        val directContext = if (hostOs == OS.Linux) {
            openGLSnapshot?.directContext
        } else {
            interop.directContext
        }
        if (directContext == null) {
            logOnce("DirectContext not initialized yet")
            return@Canvas
        }
        val width = size.width.toInt()
        val height = size.height.toInt()
        if (width <= 0 || height <= 0) return@Canvas
        // Skiko can recreate its redrawer (and render device) at runtime; the ring's
        // textures must live on Skia's current device.
        val renderDevicePtr = openGLSnapshot?.environment?.shareContext
            ?: runCatching { interop.renderDevicePtr }.getOrNull()
        renderDevicePtr?.let {
            player.refreshDeviceIfChanged(it)
        }

        val renderer = when (hostOs) {
            OS.MacOS -> "Metal"
            OS.Windows -> "D3D12"
            OS.Linux -> "OpenGL/GLX"
            else -> "unknown"
        }
        logOnce("rendering ${width}x${height} via $renderer surface", MPVLog.INFO)
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
