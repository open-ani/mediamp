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
                }

                override fun addNotify() {
                    super.addNotify()
                    mediampPlayer.attachRenderSurface(contentHandle)
                }

                override fun removeNotify() {
                    mediampPlayer.detachRenderSurface()
                    super.removeNotify()
                }
            }
        },
        modifier = modifier,
        update = {},
    )
}
