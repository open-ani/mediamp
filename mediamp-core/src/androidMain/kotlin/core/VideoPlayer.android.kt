/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.openani.mediamp.core

import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.PlayerView.ControllerVisibilityListener
import org.openani.mediamp.ExoPlayerMediampPlayer
import org.openani.mediamp.core.state.MediampPlayer

@Composable
actual fun MediaPlayerSurface(
    mediampPlayer: MediampPlayer,
    modifier: Modifier
) = MediaPlayerSurface(mediampPlayer, modifier, configuration = {})

@Composable
fun MediaPlayerSurface(
    mediampPlayer: MediampPlayer,
    modifier: Modifier = Modifier,
    configuration: PlayerView.() -> Unit = {},
) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                val videoView = this
                controllerAutoShow = false
                useController = false
                controllerHideOnTouch = false
                subtitleView?.apply {
                    this.setStyle(
                        CaptionStyleCompat(
                            Color.WHITE,
                            0x000000FF,
                            0x00000000,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            Color.BLACK,
                            Typeface.DEFAULT,
                        ),
                    )
                }
                (mediampPlayer as? ExoPlayerMediampPlayer)?.let {
                    this.player = it.exoPlayer
                    setControllerVisibilityListener(
                        ControllerVisibilityListener { visibility ->
                            if (visibility == View.VISIBLE) {
                                videoView.hideController()
                            }
                        },
                    )
                }
                configuration()
            }
        },
        modifier,
        onRelease = {
            // TODO: 2024/12/22 release player 
        },
        update = { view ->
            (mediampPlayer as? ExoPlayerMediampPlayer)?.let {
                view.player = it.exoPlayer
            }
        },
    )
}
