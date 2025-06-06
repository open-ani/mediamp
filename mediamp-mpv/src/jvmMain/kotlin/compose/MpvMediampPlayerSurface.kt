/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.openani.mediamp.mpv.compose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkikoProperties
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.MpvOffscreenSurface

@OptIn(InternalMediampApi::class)
@Composable
public fun MpvMediampPlayerSurface(
    mediampPlayer: MpvMediampPlayer,
    modifier: Modifier = Modifier,
) {
    val contextHolder = remember(mediampPlayer) { MpvRenderContext(mediampPlayer) }
    var coords: androidx.compose.ui.layout.LayoutCoordinates? = null
    DisposableEffect(Unit) {
        onDispose { contextHolder.release() }
    }
    Canvas(
        modifier.onGloballyPositioned { coords = it },
    ) {
        val layout = coords ?: return@Canvas
        if (SkikoProperties.renderApi != GraphicsApi.OPENGL) return@Canvas
        contextHolder.draw(size.width.toInt(), size.height.toInt(), drawContext.canvas.nativeCanvas)
    }
}

private class MpvRenderContext(private val player: MpvMediampPlayer) {
    private var initialized = false
    private val offscreen = MpvOffscreenSurface()

    @OptIn(InternalMediampApi::class)
    fun draw(width: Int, height: Int, dest: org.jetbrains.skia.Canvas) {
        if (!initialized) {
            player.impl.createRenderContext()
            initialized = true
        }
        offscreen.ensure(width, height)
        player.renderFrame(offscreen.fboId, width, height)
        offscreen.drawTo(dest)
    }

    @OptIn(InternalMediampApi::class)
    fun release() {
        offscreen.dispose()
        if (initialized) {
            player.impl.destroyRenderContext()
            initialized = false
        }
    }
}
