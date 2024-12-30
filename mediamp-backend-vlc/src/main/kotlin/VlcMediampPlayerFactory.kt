/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package org.openani.mediamp.backend.vlc

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.openani.mediamp.MediampPlayerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

class VlcMediampPlayerFactory : MediampPlayerFactory<VlcMediampPlayer> {
    override val forClass: KClass<VlcMediampPlayer> = VlcMediampPlayer::class
    override fun create(
        context: Any,
        parentCoroutineContext: CoroutineContext
    ): VlcMediampPlayer = VlcMediampPlayer(parentCoroutineContext)

    @Composable
    override fun Surface(
        mediampPlayer: VlcMediampPlayer,
        modifier: Modifier
    ) {
        VlcMediaPlayerSurface(mediampPlayer, modifier)
    }
}