/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.ui.Modifier
import org.openani.mediamp.MediampPlayer
import kotlin.reflect.KClass

interface MediampPlayerSurfaceProvider<T : MediampPlayer> {
    val forClass: KClass<T>

    @Composable
    @NonRestartableComposable
    fun Surface(
        mediampPlayer: T,
        modifier: Modifier
    )
}