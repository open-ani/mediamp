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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.window.LocalWindow
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.SurfaceOrigin
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.utils.OpenGLComponentProvider
import org.openani.mediamp.mpv.utils.findSkiaLayer

@OptIn(InternalMediampApi::class)
@Composable
actual fun MpvMediampPlayerSurface(
    player: MpvMediampPlayer,
    modifier: Modifier,
) {
    val window = LocalWindow.current as ComposeWindow
    val components = remember(window) {
        window.findSkiaLayer()?.let { OpenGLComponentProvider(it) }
    }

    var textureId by remember { mutableIntStateOf(0) }
    var renderContextInitialized by remember { mutableStateOf(false) }
    val interpolator = remember { FrameInterpolator() }

    DisposableEffect(components) {
        if (components == null) return@DisposableEffect onDispose { }

        onDispose {
            player.image?.close()
            player.backendTexture?.close()
            player.releaseTexture()
            player.releaseRenderContext()
        }
    }

    LaunchedEffect(interpolator) {
        interpolator.frameLoop()
    }

    Canvas(modifier = modifier) {
        interpolator.updateSubscription

        if (components == null) return@Canvas
        if (!renderContextInitialized) {
            renderContextInitialized = player.createRenderContext(components.glDevice, components.glContext)
            if (!renderContextInitialized) return@Canvas
        }
        val skiaCanvas = drawContext.canvas.nativeCanvas

        if (player.currentSize == null || player.currentSize != size || textureId == 0) {
            player.releaseTexture()

            player.image?.close()
            player.image = null
            player.backendTexture?.close()
            player.backendTexture = null

            textureId = player.createTexture(size.width.toInt(), size.height.toInt())

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
            components.directContext.resetGLAll()
        }
        player.image?.let {
            skiaCanvas.drawImage(it, 0f, 0f)
        }
    }
}
