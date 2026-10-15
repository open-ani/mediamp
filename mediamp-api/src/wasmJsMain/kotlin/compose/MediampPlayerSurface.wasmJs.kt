@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.webgl.rememberWebGLRenderTarget
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.WebMediampPlayer
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.VideoAspectRatio

@Composable
public actual fun MediampPlayerSurface(
    mediampPlayer: MediampPlayer,
    modifier: Modifier,
) {
    val webPlayer = mediampPlayer as? WebMediampPlayer
        ?: error("wasmJs MediampPlayerSurface requires WebMediampPlayer, but got ${mediampPlayer::class}")
    val video = webPlayer.videoElement
    var videoSize by remember(video) { mutableStateOf(IntSize(video.videoWidth, video.videoHeight)) }
    var frameVersion by remember(video) { mutableIntStateOf(0) }
    var subtitleText by remember(video) { mutableStateOf("") }
    val target = rememberWebGLRenderTarget(videoSize)
    if (target == null) {
        Box(modifier)
        return
    }

    DisposableEffect(video, target) {
        val observer = observeVideoFrames(video) { width, height ->
            videoSize = IntSize(width, height)
            frameVersion++
        }
        val subtitles = observeVideoSubtitles(video) { subtitleText = it }
        onDispose {
            stopObservingVideoFrames(observer)
            stopObservingVideoFrames(subtitles)
        }
    }

    LaunchedEffect(video, target, frameVersion, videoSize) {
        if (videoSize.width == 0 || videoSize.height == 0) {
            withFrameNanos {
                target.render {
                    clearVideoTexture(target.webGLContext)
                }
            }
            return@LaunchedEffect
        }
        if (!hasVideoFrame(video)) return@LaunchedEffect
        // The host context is unavailable before the first Compose frame.
        var rendered = false
        while (!rendered) {
            withFrameNanos {
                rendered = target.render {
                    uploadVideoFrame(target.webGLContext, target.webGlTexture, video)
                }
            }
        }
    }

    val mode by webPlayer.features[VideoAspectRatio]!!.mode.collectAsState()
    Box(modifier) {
        Image(
            painter = target.painter,
            contentDescription = null,
            contentScale = when (mode) {
                AspectRatioMode.FIT -> ContentScale.Fit
                AspectRatioMode.CROP -> ContentScale.Crop
                AspectRatioMode.STRETCH -> ContentScale.FillBounds
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (subtitleText.isNotBlank()) {
            BasicText(
                text = subtitleText,
                style = TextStyle(color = Color.White, textAlign = TextAlign.Center),
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.65f)).padding(4.dp),
            )
        }
    }
}

@Composable
public actual fun rememberMediampPlayer(parentCoroutineContext: () -> kotlin.coroutines.CoroutineContext): MediampPlayer {
    return remember {
        RememberedMediampPlayer(WebMediampPlayer(parentCoroutineContext = parentCoroutineContext()))
    }.player
}
