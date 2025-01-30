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
import org.openani.mediamp.compose.MediampPlayerSurfaceProvider
import org.openani.mediamp.exoplayer.ExoPlayerMediampPlayer
import kotlin.reflect.KClass

class ExoPlayerMediampPlayerSurfaceProvider : MediampPlayerSurfaceProvider<ExoPlayerMediampPlayer> {
    override val forClass: KClass<ExoPlayerMediampPlayer> = ExoPlayerMediampPlayer::class

    @Composable
    override fun Surface(mediampPlayer: ExoPlayerMediampPlayer, modifier: Modifier) {
        ExoPlayerMediampPlayerSurface(mediampPlayer, modifier)
    }
}
