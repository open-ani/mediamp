/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.MediampPlayerFactory
import org.openani.mediamp.MediampPlayerFactoryLoader
import org.openani.mediamp.internal.MediampInternalApi

@OptIn(MediampInternalApi::class)
@Composable
actual fun MediaPlayerSurface(
    mediampPlayer: MediampPlayer,
    modifier: Modifier
) {
    val factory = remember(mediampPlayer) {
        @Suppress("UNCHECKED_CAST")
        MediampPlayerFactoryLoader.getByInstance(mediampPlayer)
                as MediampPlayerFactory<MediampPlayer>
    }

    factory.Surface(mediampPlayer, modifier)
}