/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import org.openani.mediamp.exoplayer.ExoPlayerMediampPlayer

@Composable
fun ExoPlayerMediampPlayerSurface(
    mediampPlayer: ExoPlayerMediampPlayer,
    modifier: Modifier = Modifier,
    configuration: PlayerView.() -> Unit = {},
) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                this.player = mediampPlayer.impl
                configuration()
            }
        },
        modifier,
        onRelease = {
        },
        update = { view ->
            view.player = mediampPlayer.impl
        },
    )

}
