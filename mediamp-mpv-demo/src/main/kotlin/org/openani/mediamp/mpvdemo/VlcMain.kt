/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpvdemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.togglePause
import org.openani.mediamp.vlc.VlcMediampPlayer
import org.openani.mediamp.vlc.compose.VlcMediampPlayerSurface
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Baseline for benchmarking: the current production stack (VLC via vlcj callback
 * surface, software decode, per-frame bitmap upload) with the exact same window
 * size and Compose overlay as the mpv demo.
 */
fun main(args: Array<String>) {
    val videoPath = args.firstOrNull()
        ?: System.getProperty("mpvdemo.video")
        ?: "${System.getProperty("user.home")}/.cache/mediamp-mpv-demo/test-1080p-h264.mp4"

    singleWindowApplication(
        title = "mediamp VLC baseline",
        state = WindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        val player = remember { VlcMediampPlayer(EmptyCoroutineContext) }
        DisposableEffect(Unit) {
            onDispose { player.close() }
        }

        LaunchedEffect(player) {
            // vlcj expects a plain path or MRL; a file: URI would be resolved relative to cwd.
            player.setMediaData(UriMediaData(videoPath))
            player.resume()
        }
        // Loop for sustained benchmarking, mirroring mpv's loop-file=inf.
        LaunchedEffect(player) {
            player.playbackState.collect { state ->
                if (state == PlaybackState.FINISHED) {
                    player.seekTo(0)
                    player.resume()
                }
            }
        }

        val playbackState by player.playbackState.collectAsState()
        val positionMillis by player.currentPositionMillis.collectAsState()
        val mediaProperties by player.mediaProperties.collectAsState()

        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                VlcMediampPlayerSurface(player, Modifier.fillMaxSize())
                DemoOverlay(
                    title = "Compose overlay on VLC (software callback)",
                    statusLine = "backend: vlcj CallbackVideoSurface   state: $playbackState",
                    statusOk = playbackState == PlaybackState.PLAYING,
                    paused = playbackState == PlaybackState.PAUSED,
                    positionSeconds = positionMillis / 1000.0,
                    durationSeconds = (mediaProperties?.durationMillis ?: 0L) / 1000.0,
                    onTogglePause = { player.togglePause() },
                    onSeek = { player.seekTo((it * 1000).toLong()) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
