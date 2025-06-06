/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.openani.mediamp.mpv.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.MpvMediampPlayer

@OptIn(InternalMediampApi::class)
@Composable
public fun MpvMediampPlayerSurface(
    mediampPlayer: MpvMediampPlayer,
    modifier: Modifier = Modifier,
) {
    SwingPanel(
        factory = {
            object : SkiaLayer() {
                init {
                    renderApi = GraphicsApi.OPENGL
                    mediampPlayer.attachRenderSurface(contentHandle)
//                    renderDelegate = object : SkikoRenderDelegate {
//                        override fun onRender(
//                            canvas: org.jetbrains.skia.Canvas,
//                            width: Int,
//                            height: Int,
//                            nanoTime: Long
//                        ) {
//                            canvas._ptr
//                            val gl = OpenGLApi.instance
//                            val fbo = gl.glGetIntegerv(gl.GL_DRAW_FRAMEBUFFER_BINDING)
//                            mediampPlayer.renderFrame(fbo, width, height)
//                        }
//
////                        override fun onReshape(width: Int, height: Int) {}
//                    }
                }

                override fun dispose() {
                    super.dispose()
                    mediampPlayer.detachRenderSurface()
                }
            }
        },
        modifier = modifier,
        update = {},
    )
}
