/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpvdemo

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import java.awt.Window

/**
 * Renders the mpv video into the Compose scene graph: mpv draws (GL, hwdec=videotoolbox)
 * into an IOSurface, which Skia samples as a Metal texture. Because the video is just a
 * canvas draw call, any Compose content stacked above this composable composites normally.
 */
@Composable
fun MpvVideoSurface(player: MpvPlayer, window: Window, modifier: Modifier = Modifier) {
    val interop = remember(window) {
        window.findSkiaLayer()?.let { runCatching { SkiaMetalInterop(it) }.getOrElse { e -> e.printStackTrace(); null } }
    }
    val frameTick = remember { mutableLongStateOf(0L) }

    DisposableEffect(player) {
        val listener = object : MpvNative.UpdateListener {
            override fun onRenderUpdate() {
                frameTick.longValue++
            }
        }
        player.setUpdateListener(listener)
        onDispose {
            player.setUpdateListener(null)
            player.releaseSurface()
        }
    }

    Canvas(modifier) {
        frameTick.longValue // subscribe: redraw whenever mpv reports a new frame

        val skiaInterop = interop ?: return@Canvas
        val directContext = skiaInterop.directContext ?: return@Canvas
        val width = size.width.toInt()
        val height = size.height.toInt()
        if (width <= 0 || height <= 0) return@Canvas

        if (!player.ensureSurface(width, height, skiaInterop.mtlDevicePtr, directContext)) return@Canvas
        player.renderFrame()
        player.skiaSurface?.draw(drawContext.canvas.nativeCanvas, 0, 0, null)
    }
}
