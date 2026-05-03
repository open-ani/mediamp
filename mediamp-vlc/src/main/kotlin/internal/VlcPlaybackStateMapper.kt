/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.vlc.internal

import org.openani.mediamp.PlaybackState

internal class VlcPlaybackStateMapper {
    private var hasPlaybackStarted: Boolean = false

    fun reset() {
        hasPlaybackStarted = false
    }

    fun onPlaying(currentState: PlaybackState): PlaybackState? {
        if (currentState <= PlaybackState.FINISHED) {
            return null
        }

        hasPlaybackStarted = true
        return PlaybackState.PLAYING
    }

    fun onPaused(currentState: PlaybackState): PlaybackState? = when (currentState) {
        PlaybackState.PLAYING,
        PlaybackState.PAUSED_BUFFERING,
        -> PlaybackState.PAUSED

        else -> null
    }

    fun onBuffering(currentState: PlaybackState, bufferedPercentage: Float): PlaybackState? {
        if (!hasPlaybackStarted) {
            return null
        }

        return if (bufferedPercentage < 100f) {
            when (currentState) {
                PlaybackState.PLAYING,
                PlaybackState.PAUSED_BUFFERING,
                -> PlaybackState.PAUSED_BUFFERING

                else -> null
            }
        } else {
            if (currentState == PlaybackState.PAUSED_BUFFERING) {
                PlaybackState.PLAYING
            } else {
                null
            }
        }
    }
}
