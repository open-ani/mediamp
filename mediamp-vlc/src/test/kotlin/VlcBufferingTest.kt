/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.vlc

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.openani.mediamp.PlaybackState
import kotlin.test.Test
import kotlin.test.assertTrue

class VlcBufferingTest {
    @Test
    fun `paused buffering state is exposed by buffering feature`() = runBlocking {
        val buffering = VlcBuffering(
            currentPositionMillis = MutableStateFlow(0L),
            playbackState = MutableStateFlow(PlaybackState.PAUSED_BUFFERING),
        )

        assertTrue(buffering.isBuffering.first())
    }
}
