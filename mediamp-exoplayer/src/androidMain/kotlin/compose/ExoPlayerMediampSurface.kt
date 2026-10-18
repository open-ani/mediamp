/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.compose

import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.openani.mediamp.exoplayer.ExoPlayerMediampPlayer
import org.openani.mediamp.exoplayer.internal.StillFrameBufferSize
import org.openani.mediamp.exoplayer.internal.videoDisplaySizeOrNull
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.VideoAspectRatio

@OptIn(UnstableApi::class)
@Composable
fun ExoPlayerMediampPlayerSurface(
    mediampPlayer: ExoPlayerMediampPlayer,
    modifier: Modifier = Modifier,
    configuration: PlayerView.() -> Unit = {},
) {
    val aspectRatioMode by mediampPlayer.features[VideoAspectRatio.Key]?.mode?.collectAsState() 
        ?: return // Return early if VideoAspectRatio feature is not available
    
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                this.player = mediampPlayer.impl
                configuration()
            }.also { playerView = it }
        },
        modifier,
        update = { view ->
            view.player = mediampPlayer.impl
            // Apply aspect ratio mode to PlayerView
            view.resizeMode = when (aspectRatioMode) {
                AspectRatioMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectRatioMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                AspectRatioMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
    )

    val player = mediampPlayer.impl
    val view = playerView
    if (view != null) {
        DisposableEffect(view, player) {
            // PlayerView takes the content frame aspect from Player.getVideoSize(): once in
            // setPlayer, and after that only from its own onVideoSizeChanged, which returns
            // early on VideoSize.UNKNOWN. Under video effects media3 1.9.0 never reports the
            // real size, so the aspect keeps the 0 that setPlayer left it with and
            // AspectRatioFrameLayout.onMeasure returns early, letting the frame fill its
            // parent. The selected track's Format is the only remaining source.
            val listener = object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) = applyVideoAspectRatioFallback(view, player)
                override fun onVideoSizeChanged(videoSize: VideoSize) = applyVideoAspectRatioFallback(view, player)
            }
            player.addListener(listener)
            // Tracks may already be known by the time the view exists.
            applyVideoAspectRatioFallback(view, player)
            onDispose { player.removeListener(listener) }
        }
        val surfaceView = view.videoSurfaceView as? SurfaceView
        if (surfaceView != null) {
            DisposableEffect(surfaceView, player) {
                val stillFrameBufferSize = StillFrameBufferSize(surfaceView, player)
                onDispose { stillFrameBufferSize.release() }
            }
        }
    }
}

@OptIn(UnstableApi::class)
private fun applyVideoAspectRatioFallback(view: PlayerView, player: ExoPlayer) {
    if (player.videoSize.width > 0 && player.videoSize.height > 0) {
        return // PlayerView applies the aspect ratio itself in this case.
    }
    val size = player.videoDisplaySizeOrNull() ?: return
    val contentFrame = view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        ?: return
    contentFrame.setAspectRatio(size.width.toFloat() / size.height)
}
