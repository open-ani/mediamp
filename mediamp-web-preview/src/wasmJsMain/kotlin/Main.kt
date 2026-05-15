@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.coroutines.launch
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.WebMediampPlayer
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.metadata.orEmpty
import org.openani.mediamp.playUri
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.Subtitle
import org.openani.mediamp.source.UriMediaData
import org.w3c.dom.HTMLElement

private const val DefaultVideo =
    "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4"

private const val DefaultSubtitle =
    "https://interactive-examples.mdn.mozilla.net/media/examples/friday.vtt"

public fun main() {
    val root = (document.getElementById("root") ?: document.body!!) as HTMLElement
    ComposeViewport(root) {
        PreviewApp()
    }
}

@Composable
private fun PreviewApp() {
    val player = remember { WebMediampPlayer() }
    val scope = rememberCoroutineScope()

    DisposableEffect(player) {
        onDispose { player.close() }
    }

    var uri by remember { mutableStateOf(DefaultVideo) }
    var subtitleUri by remember { mutableStateOf(DefaultSubtitle) }
    var statusText by remember { mutableStateOf("Ready") }
    val playbackState by player.playbackState.collectAsState()
    val positionMillis by player.currentPositionMillis.collectAsState()
    val properties by player.mediaProperties.collectAsState()
    val audio = player.features[AudioLevelController]
    val volume by audio?.volume?.collectAsState() ?: remember { mutableStateOf(1f) }
    val speed = player.features[PlaybackSpeed]

    LaunchedEffect(Unit) {
        player.setPreviewMedia(uri, subtitleUri)
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color(0xff111318)) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    MediampPlayerSurface(
                        player,
                        Modifier.fillMaxSize(),
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xff1b1f26))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                scope.launch {
                                    if (playbackState == PlaybackState.PLAYING) {
                                        player.pause()
                                    } else {
                                        player.resume()
                                    }
                                }
                            },
                        ) {
                            Text(if (playbackState == PlaybackState.PLAYING) "Pause" else "Play")
                        }
                        Button(onClick = { player.seekTo((player.getCurrentPositionMillis() - 10_000L).coerceAtLeast(0L)) }) {
                            Text("-10s")
                        }
                        Button(onClick = { player.seekTo(player.getCurrentPositionMillis() + 10_000L) }) {
                            Text("+10s")
                        }
                        Button(onClick = { audio?.setMute(audio.isMute.value.not()) }) {
                            Text(if (audio?.isMute?.value == true) "Unmute" else "Mute")
                        }
                        Text("State: $playbackState", color = Color.White)
                        Text(statusText, color = Color(0xffaeb6c2))
                    }

                    val durationMillis = properties.orEmpty().durationMillis.takeIf { it > 0L } ?: 1L
                    Slider(
                        value = positionMillis.coerceIn(0L, durationMillis).toFloat(),
                        valueRange = 0f..durationMillis.toFloat(),
                        onValueChange = { player.seekTo(it.toLong()) },
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatMillis(positionMillis), color = Color.White)
                        Text("/", color = Color(0xffaeb6c2))
                        Text(formatMillis(properties.orEmpty().durationMillis), color = Color.White)
                        Text("Volume", color = Color(0xffaeb6c2))
                        Slider(
                            value = volume,
                            valueRange = 0f..1f,
                            onValueChange = { audio?.setVolume(it) },
                            modifier = Modifier.width(160.dp),
                        )
                        Text("Speed", color = Color(0xffaeb6c2))
                        Button(onClick = { speed?.set(1f) }) { Text("1x") }
                        Button(onClick = { speed?.set(1.5f) }) { Text("1.5x") }
                        Button(onClick = { speed?.set(2f) }) { Text("2x") }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = uri,
                            onValueChange = { uri = it },
                            label = { Text("Video URL") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = subtitleUri,
                            onValueChange = { subtitleUri = it },
                            label = { Text("Subtitle URL") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    statusText = "Loading"
                                    runCatching { player.setPreviewMedia(uri, subtitleUri) }
                                        .onSuccess { statusText = "Loaded" }
                                        .onFailure { statusText = it.message ?: "Load failed" }
                                }
                            },
                        ) {
                            Text("Load")
                        }
                    }
                }
            }
        }
    }
}

private suspend fun MediampPlayer.setPreviewMedia(uri: String, subtitleUri: String) {
    if (subtitleUri.isBlank()) {
        playUri(uri)
    } else {
        setMediaData(
            UriMediaData(
                uri,
                extraFiles = MediaExtraFiles(
                    subtitles = listOf(
                        Subtitle(
                            uri = subtitleUri,
                            mimeType = "text/vtt",
                            language = "en",
                            label = "English",
                        ),
                    ),
                ),
            ),
        )
    }
}

private fun formatMillis(value: Long): String {
    if (value < 0L) return "--:--"
    val totalSeconds = value / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
