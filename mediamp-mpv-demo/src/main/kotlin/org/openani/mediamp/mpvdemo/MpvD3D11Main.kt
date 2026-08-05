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
import kotlinx.coroutines.flow.filterIsInstance
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.isLoadingOrBuffering
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.togglePlayWhenReady

/**
 * Smoke-test entry for the production mediamp-mpv render path (Windows D3D11 / macOS
 * Metal / Linux GLX): the real [MpvMediampPlayer] + [MpvMediampPlayerSurface], loading
 * the native runtime from a local mpv assemble output.
 *
 * Run: ./gradlew :mediamp-mpv-demo:runD3D11 [-Pvideo=/path/to.mp4]
 * Plays FFmpeg libavdevice's lavfi test source when no video is given.
 */
fun main(args: Array<String>) {
    val videoUri = args.firstOrNull()
        ?: System.getProperty("mpvdemo.video")
        ?: "av://lavfi:testsrc2=size=1280x720:rate=60"

    val hostRuntimeTask = when {
        System.getProperty("os.name").contains("Windows") ->
            if (System.getProperty("os.arch") == "aarch64") "mpvAssembleWindowsArm64" else "mpvAssembleWindowsX64"
        System.getProperty("os.name").contains("Linux") -> "mpvAssembleLinuxX64"
        System.getProperty("os.arch") == "aarch64" -> "mpvAssembleMacosArm64"
        else -> "mpvAssembleMacosX64"
    }
    val runtimeDir = requireNotNull(System.getProperty("mediamp.mpv.runtime.dir")) {
        "mediamp.mpv.runtime.dir must point at an assembled mpv runtime " +
            "(run :mediamp-mpv:$hostRuntimeTask first)"
    }
    MpvMediampPlayer.prepareLibraries(runtimeDir, extractRuntimeLibrary = false)

    singleWindowApplication(
        title = "mediamp mpv production renderer (D3D11 / Metal / GLX)",
        state = WindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        val player = remember { MpvMediampPlayer(Any(), Dispatchers.Default) }
        DisposableEffect(Unit) {
            onDispose { player.close() }
        }

        var loadError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(player) {
            try {
                player.setMediaData(
                    UriMediaData(videoUri, emptyMap(), MediaExtraFiles.EMPTY),
                    playWhenReady = true,
                )
            } catch (e: PlaybackException) {
                loadError = e.toString()
            }
        }
        // Loop for sustained smoke runs. Session-advancing reactions use `events`, not the
        // conflated `state`; at Ended, play() replays from the beginning (state-spec v2).
        LaunchedEffect(player) {
            player.events.filterIsInstance<PlaybackEvent.MediaEnded>().collect {
                player.play()
            }
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

        val playerState by player.state.collectAsState()
        val positionMillis by player.currentPositionMillis.collectAsState()
        val properties by player.mediaProperties.collectAsState()

        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                MpvMediampPlayerSurface(player, Modifier.fillMaxSize())
                DemoOverlay(
                    title = "mediamp-mpv production path (D3D11/Metal/GLX)",
                    statusLine = loadError
                        ?: "mediaStatus: ${playerState.mediaStatus}   state: $playerState   uri: $videoUri",
                    statusOk = loadError == null && playerState.isPlaying,
                    // Button icon derives from the intent axis, never from buffering.
                    paused = !playerState.playWhenReady,
                    isLoading = playerState.isLoadingOrBuffering,
                    positionSeconds = positionMillis / 1000.0,
                    durationSeconds = (properties?.durationMillis ?: 0L) / 1000.0,
                    // Never dead in any loaded state; replays at Ended.
                    onTogglePause = { player.togglePlayWhenReady() },
                    onSeek = { player.seekTo((it * 1000).toLong()) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
