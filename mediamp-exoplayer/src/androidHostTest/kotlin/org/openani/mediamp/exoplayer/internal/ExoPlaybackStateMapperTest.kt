/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.internal

import androidx.media3.common.Player
import org.openani.mediamp.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExoPlaybackStateMapperTest {
    @Test
    fun `initial ready stays ready before first playback`() {
        val mapper = ExoPlaybackStateMapper()

        val mapped = mapper.resolve(
            exoPlaybackState = Player.STATE_READY,
            playWhenReady = true,
            isPlaying = false,
            currentState = PlaybackState.READY,
        )

        assertEquals(PlaybackState.READY, mapped)
    }

    @Test
    fun `buffering after playback started maps to paused buffering`() {
        val mapper = ExoPlaybackStateMapper()
        mapper.resolve(
            exoPlaybackState = Player.STATE_READY,
            playWhenReady = true,
            isPlaying = true,
            currentState = PlaybackState.READY,
        )

        val mapped = mapper.resolve(
            exoPlaybackState = Player.STATE_BUFFERING,
            playWhenReady = true,
            isPlaying = false,
            currentState = PlaybackState.PLAYING,
        )

        assertEquals(PlaybackState.PAUSED_BUFFERING, mapped)
    }

    @Test
    fun `paused seek completion restores paused instead of ready`() {
        val mapper = ExoPlaybackStateMapper()
        mapper.resolve(
            exoPlaybackState = Player.STATE_READY,
            playWhenReady = true,
            isPlaying = true,
            currentState = PlaybackState.READY,
        )

        mapper.resolve(
            exoPlaybackState = Player.STATE_BUFFERING,
            playWhenReady = false,
            isPlaying = false,
            currentState = PlaybackState.PAUSED,
        )

        val mapped = mapper.resolve(
            exoPlaybackState = Player.STATE_READY,
            playWhenReady = false,
            isPlaying = false,
            currentState = PlaybackState.PAUSED_BUFFERING,
        )

        assertEquals(PlaybackState.PAUSED, mapped)
    }

    @Test
    fun `idle state does not force a transition`() {
        val mapper = ExoPlaybackStateMapper()

        val mapped = mapper.resolve(
            exoPlaybackState = Player.STATE_IDLE,
            playWhenReady = false,
            isPlaying = false,
            currentState = PlaybackState.CREATED,
        )

        assertNull(mapped)
    }
}
