/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.backend.vlc.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.openani.mediamp.backend.vlc.VlcMediampPlayer
import org.openani.mediamp.compose.MediampPlayerSurfaceProvider
import kotlin.reflect.KClass

class VlcMediampPlayerSurfaceProvider : MediampPlayerSurfaceProvider<VlcMediampPlayer> {
    override val forClass: KClass<VlcMediampPlayer> = VlcMediampPlayer::class

    @Composable
    override fun Surface(mediampPlayer: VlcMediampPlayer, modifier: Modifier) {
        VlcMediaPlayerSurface(mediampPlayer, modifier)
    }
}