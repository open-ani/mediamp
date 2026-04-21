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
expect class MpvMediampPlayer : MediampPlayer {
    override val impl: Any
    override val playbackState: StateFlow<PlaybackState>
    override val mediaData: Flow<MediaData?>
    override val mediaProperties: StateFlow<MediaProperties?>
    override fun getCurrentMediaProperties(): MediaProperties?
    override val currentPositionMillis: StateFlow<Long>
    override val playbackProgress: Flow<Float>
    override val features: PlayerFeatures
    override suspend fun setMediaData(data: MediaData)
    override fun getCurrentPlaybackState(): PlaybackState
    override fun getCurrentPositionMillis(): Long
    override fun resume()
    override fun pause()
    override fun stopPlayback()
    override fun seekTo(positionMillis: Long)
    override fun close()
}
