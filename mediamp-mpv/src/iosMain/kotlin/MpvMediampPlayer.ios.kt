/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.source.MediaData

@OptIn(InternalForInheritanceMediampApi::class)
actual class MpvMediampPlayer : MediampPlayer {
    actual override val impl: Any
        get() = TODO("Not yet implemented")
    actual override val playbackState: StateFlow<PlaybackState>
        get() = TODO("Not yet implemented")
    actual override val mediaData: Flow<MediaData?>
        get() = TODO("Not yet implemented")
    actual override val mediaProperties: StateFlow<MediaProperties?>
        get() = TODO("Not yet implemented")

    actual override fun getCurrentMediaProperties(): MediaProperties? {
        TODO("Not yet implemented")
    }

    actual override val currentPositionMillis: StateFlow<Long>
        get() = TODO("Not yet implemented")
    actual override val playbackProgress: Flow<Float>
        get() = TODO("Not yet implemented")
    actual override val features: PlayerFeatures
        get() = TODO("Not yet implemented")

    actual override suspend fun setMediaData(data: MediaData) {
    }

    actual override fun getCurrentPlaybackState(): PlaybackState {
        TODO("Not yet implemented")
    }

    actual override fun getCurrentPositionMillis(): Long {
        TODO("Not yet implemented")
    }

    actual override fun resume() {
    }

    actual override fun pause() {
    }

    actual override fun stopPlayback() {
    }

    actual override fun seekTo(positionMillis: Long) {
    }

    actual override fun close() {
    }
}
