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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication
import kotlinx.coroutines.delay

fun main(args: Array<String>) {
    val videoPath = args.firstOrNull()
        ?: System.getProperty("mpvdemo.video")
        ?: "${System.getProperty("user.home")}/.cache/mediamp-mpv-demo/test-1080p-h264.mp4"

    singleWindowApplication(
        title = "mediamp mpv+Metal prototype",
        state = WindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        val player = remember { MpvPlayer().also { it.loadFile(videoPath) } }
        DisposableEffect(Unit) {
            onDispose { player.close() }
        }

        var hwdec by remember { mutableStateOf("...") }
        var videoFormat by remember { mutableStateOf("...") }
        var fps by remember { mutableDoubleStateOf(0.0) }
        var timePos by remember { mutableDoubleStateOf(0.0) }
        var duration by remember { mutableDoubleStateOf(0.0) }
        var paused by remember { mutableStateOf(false) }

        LaunchedEffect(player) {
            while (true) {
                hwdec = player.hwdecCurrent ?: "none"
                videoFormat = player.videoFormat ?: "?"
                fps = player.estimatedVfFps
                timePos = player.timePos
                duration = player.duration
                paused = player.isPaused
                delay(500)
            }
        }

        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                MpvVideoSurface(player, window, Modifier.fillMaxSize())
                DemoOverlay(
                    title = "Compose overlay on mpv (hwdec)",
                    statusLine = "hwdec-current: $hwdec   video-format: $videoFormat   estimated-vf-fps: ${"%.1f".format(fps)}",
                    statusOk = hwdec.contains("videotoolbox"),
                    paused = paused,
                    positionSeconds = timePos,
                    durationSeconds = duration,
                    onTogglePause = { player.togglePause() },
                    onSeek = { player.seekAbsolute(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
