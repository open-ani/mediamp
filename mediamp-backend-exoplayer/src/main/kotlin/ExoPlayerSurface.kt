/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.backend.exoplayer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView

@Composable
fun ExoPlayerSurface(
    mediampPlayer: ExoPlayerMediampPlayer,
    modifier: Modifier = Modifier,
    configuration: PlayerView.() -> Unit = {},
) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                this.player = mediampPlayer.exoPlayer
                configuration()
            }
        },
        modifier,
        onRelease = {
            // TODO: 2024/12/22 release player 
        },
        update = { view ->
            view.player = mediampPlayer.exoPlayer
        },
    )

}
