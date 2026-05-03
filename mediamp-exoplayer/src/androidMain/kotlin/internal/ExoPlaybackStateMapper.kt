/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.internal

import androidx.annotation.MainThread
import androidx.media3.common.Player
import org.openani.mediamp.PlaybackState

@MainThread
internal class ExoPlaybackStateMapper {
    var hasPlaybackStarted: Boolean = false
        private set

    fun reset() {
        hasPlaybackStarted = false
    }

    fun resolve(
        exoPlaybackState: Int,
        playWhenReady: Boolean,
        isPlaying: Boolean,
        currentState: PlaybackState,
    ): PlaybackState? {
        if (isPlaying) {
            hasPlaybackStarted = true
            return PlaybackState.PLAYING
        }

        return when (exoPlaybackState) {
            Player.STATE_BUFFERING -> {
                if (!hasPlaybackStarted || currentState < PlaybackState.READY) {
                    null
                } else {
                    PlaybackState.PAUSED_BUFFERING
                }
            }

            Player.STATE_READY -> when {
                !hasPlaybackStarted -> PlaybackState.READY
                !playWhenReady -> PlaybackState.PAUSED
                currentState == PlaybackState.PAUSED_BUFFERING -> PlaybackState.PAUSED_BUFFERING
                else -> null
            }

            Player.STATE_ENDED -> PlaybackState.FINISHED
            else -> null
        }
    }
}
