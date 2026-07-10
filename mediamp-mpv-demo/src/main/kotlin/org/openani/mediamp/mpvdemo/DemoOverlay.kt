/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpvdemo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The identical overlay used by both the mpv and VLC demos so benchmark numbers
 * include the same Compose workload (infinite animations force ~60fps redraw).
 */
@Composable
fun DemoOverlay(
    title: String,
    statusLine: String,
    statusOk: Boolean,
    paused: Boolean,
    positionSeconds: Double,
    durationSeconds: Double,
    onTogglePause: () -> Unit,
    onSeek: (seconds: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        // Top status bar: semi-transparent gradient proves alpha compositing over video.
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                .padding(16.dp)
                .align(Alignment.TopCenter),
        ) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                statusLine,
                color = if (statusOk) Color(0xFF7CFC00) else Color.Yellow,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // Continuously animating Compose elements over the video.
        val transition = rememberInfiniteTransition()
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .size(96.dp)
                .rotate(angle)
                .background(Color(0x66FF4081), RoundedCornerShape(16.dp)),
        )
        CircularProgressIndicator(
            Modifier.align(Alignment.TopEnd).padding(24.dp).size(48.dp),
            color = Color.White,
        )

        // Bottom control bar.
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .padding(16.dp)
                .align(Alignment.BottomCenter),
        ) {
            Slider(
                value = if (durationSeconds > 0 && positionSeconds.isFinite()) {
                    (positionSeconds / durationSeconds).toFloat().coerceIn(0f, 1f)
                } else 0f,
                onValueChange = { fraction ->
                    if (durationSeconds > 0) onSeek(fraction * durationSeconds)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onTogglePause) {
                    Text(if (paused) "Play" else "Pause")
                }
                Text(
                    "${formatTime(positionSeconds)} / ${formatTime(durationSeconds)}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

fun formatTime(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0) return "--:--"
    val total = seconds.toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
