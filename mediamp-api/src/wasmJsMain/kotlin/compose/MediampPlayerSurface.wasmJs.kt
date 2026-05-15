@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.WebElementView
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.WebMediampPlayer

@Composable
public actual fun MediampPlayerSurface(
    mediampPlayer: MediampPlayer,
    modifier: Modifier,
) {
    val webPlayer = mediampPlayer as? WebMediampPlayer
        ?: error("wasmJs MediampPlayerSurface requires WebMediampPlayer, but got ${mediampPlayer::class}")

    WebElementView(
        factory = { webPlayer.videoElement },
        modifier = modifier,
        update = { element ->
            element.controls = false
            element.playsInline = true
        },
    )
}

@Composable
public actual fun rememberMediampPlayer(parentCoroutineContext: () -> kotlin.coroutines.CoroutineContext): MediampPlayer {
    return remember {
        RememberedMediampPlayer(WebMediampPlayer(parentCoroutineContext = parentCoroutineContext()))
    }.player
}
