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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication
import kotlinx.coroutines.Dispatchers
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData

/**
 * Smoke-test entry for the production mediamp-mpv render path (Windows D3D11 / macOS
 * Metal): the real [MpvMediampPlayer] + [MpvMediampPlayerSurface], loading the native
 * runtime from a local mpv assemble output.
 *
 * Run: ./gradlew :mediamp-mpv-demo:runD3D11 [-Pvideo=/path/to.mp4]
 * Plays mpv's built-in lavfi test source when no video is given.
 */
fun main(args: Array<String>) {
    val videoUri = args.firstOrNull()
        ?: System.getProperty("mpvdemo.video")
        ?: "av://lavfi:testsrc2=size=1280x720:rate=60"

    val runtimeDir = requireNotNull(System.getProperty("mediamp.mpv.runtime.dir")) {
        "mediamp.mpv.runtime.dir must point at the mpv runtime (run :mediamp-mpv:mpvAssembleWindowsX64 first)"
    }
    MpvMediampPlayer.prepareLibraries(runtimeDir, extractRuntimeLibrary = false)

    singleWindowApplication(
        title = "mediamp mpv D3D11",
        state = WindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        val player = remember { MpvMediampPlayer(Any(), Dispatchers.Default) }
        DisposableEffect(Unit) {
            onDispose { player.close() }
        }

        var loadError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(player) {
            runCatching {
                player.setMediaData(UriMediaData(videoUri, emptyMap(), MediaExtraFiles.EMPTY))
                player.resume()
            }.onFailure { loadError = it.toString() }
        }

        // Smoke-test hook: -Dmpvdemo.screenshot.dir=<dir> dumps a frame readback every
        // 2s (native surface-ring PNG path), so playback can be pixel-verified.
        val screenshotDir = remember { System.getProperty("mpvdemo.screenshot.dir") }
        if (screenshotDir != null) {
            LaunchedEffect(player) {
                val screenshots = player.features[org.openani.mediamp.features.Screenshots.Key] ?: return@LaunchedEffect
                var index = 0
                while (true) {
                    kotlinx.coroutines.delay(2_000)
                    val path = "$screenshotDir/frame-${index++}.png"
                    val result = runCatching { screenshots.takeScreenshot(path) }
                    println("[demo] screenshot #$index -> $path: ${result.exceptionOrNull() ?: "ok"}")
                }
            }
        }

        val playbackState by player.playbackState.collectAsState()
        val positionMillis by player.currentPositionMillis.collectAsState()
        val properties by player.mediaProperties.collectAsState()

        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                MpvMediampPlayerSurface(player, Modifier.fillMaxSize())
                DemoOverlay(
                    title = "mediamp-mpv production path (D3D11/Metal)",
                    statusLine = loadError
                        ?: "state: $playbackState   uri: $videoUri",
                    statusOk = loadError == null && playbackState == org.openani.mediamp.PlaybackState.PLAYING,
                    paused = playbackState == org.openani.mediamp.PlaybackState.PAUSED,
                    positionSeconds = positionMillis / 1000.0,
                    durationSeconds = (properties?.durationMillis ?: 0L).coerceAtLeast(0L) / 1000.0,
                    onTogglePause = {
                        if (playbackState == org.openani.mediamp.PlaybackState.PAUSED) player.resume() else player.pause()
                    },
                    onSeek = { player.seekTo((it * 1000).toLong()) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
