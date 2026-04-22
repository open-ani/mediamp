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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.window.LocalWindow
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLBackendState
import org.jetbrains.skia.Image
import org.jetbrains.skia.SurfaceOrigin
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.utils.OpenGLComponentProvider
import org.openani.mediamp.mpv.utils.findSkiaLayer
import kotlin.time.measureTime

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
    
    var renderTimeDelta by remember { mutableLongStateOf(0L) }

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

    Box(modifier = modifier) {
        Canvas(Modifier.matchParentSize()) {
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
                renderTimeDelta = measureTime { player.renderFrame() }.inWholeMilliseconds
                components.resetContextGLAfterMpvRender()
            }
            player.image?.let {
                skiaCanvas.drawImage(it, 0f, 0f)
            }
        }

        Text(
            "renderTimeDelta: $renderTimeDelta", 
            color = Color.Red,
        )
    }
}
