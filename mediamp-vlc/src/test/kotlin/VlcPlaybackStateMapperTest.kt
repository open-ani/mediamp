/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.vlc

import org.openani.mediamp.PlaybackState
import org.openani.mediamp.vlc.internal.VlcPlaybackStateMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VlcPlaybackStateMapperTest {
    @Test
    fun `buffering while playing maps to paused buffering then back to playing`() {
        val mapper = VlcPlaybackStateMapper()

        assertEquals(PlaybackState.PLAYING, mapper.onPlaying(PlaybackState.READY))
        assertEquals(PlaybackState.PAUSED_BUFFERING, mapper.onBuffering(PlaybackState.PLAYING, 20f))
        assertEquals(PlaybackState.PLAYING, mapper.onBuffering(PlaybackState.PAUSED_BUFFERING, 100f))
    }

    @Test
    fun `resume can transition directly from ready to playing`() {
        val mapper = VlcPlaybackStateMapper()

        mapper.onPlayRequested(PlaybackState.READY)

        assertEquals(PlaybackState.PLAYING, mapper.onPlaying(PlaybackState.READY))
    }

    @Test
    fun `initial buffering after resume maps ready to paused buffering until playing callback`() {
        val mapper = VlcPlaybackStateMapper()

        assertNull(mapper.onBuffering(PlaybackState.READY, 20f))

        mapper.onPlayRequested(PlaybackState.READY)

        assertEquals(PlaybackState.PAUSED_BUFFERING, mapper.onBuffering(PlaybackState.READY, 20f))
        assertNull(mapper.onBuffering(PlaybackState.PAUSED_BUFFERING, 100f))
        assertEquals(PlaybackState.PLAYING, mapper.onPlaying(PlaybackState.PAUSED_BUFFERING))
    }

    @Test
    fun `paused player stays paused during seek buffering`() {
        val mapper = VlcPlaybackStateMapper()
        mapper.onPlaying(PlaybackState.READY)

        assertNull(mapper.onBuffering(PlaybackState.PAUSED, 20f))
        assertNull(mapper.onBuffering(PlaybackState.PAUSED, 100f))
    }

    @Test
    fun `paused callback only settles active playback states`() {
        val mapper = VlcPlaybackStateMapper()

        assertNull(mapper.onPaused(PlaybackState.READY))
        assertEquals(PlaybackState.PAUSED, mapper.onPaused(PlaybackState.PLAYING))
        assertEquals(PlaybackState.PAUSED, mapper.onPaused(PlaybackState.PAUSED_BUFFERING))
    }
}
