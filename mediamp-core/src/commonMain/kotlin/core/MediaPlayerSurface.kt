/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.core

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.openani.mediamp.core.state.MediampPlayer

/**
 * Displays the media content (e.g. a video) of [MediampPlayer].
 *
 * There is no control bar or any other UI elements, it's instead, similar to a [androidx.compose.ui.graphics.Canvas].
 *
 * The view takes all available space by default ([Modifier.fillMaxSize]).
 * You can pass an appropriate size [Modifier] to control the size of the player.
 *
 * The surface is center-aligned, fitting the available space while maintaining the aspect ratio.
 */
@Composable
expect fun MediaPlayerSurface(
    mediampPlayer: MediampPlayer,
    modifier: Modifier = Modifier, // no default value because we require a size modifier
)
