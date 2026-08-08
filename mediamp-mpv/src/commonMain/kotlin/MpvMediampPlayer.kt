/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlayerState
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.source.MediaData

/**
 * [MediampPlayer] backed by libmpv.
 *
 * The playback state model is implemented by the shared state machine
 * ([org.openani.mediamp.AbstractMediampPlayer], spec: `docs/playback-state-v2.md`); the mpv
 * backend adapts native mpv events and properties onto it.
 */
// The abstract interface members are re-declared: a non-abstract expect class must satisfy
// its supertype itself for metadata compilation (the actuals inherit them from the shared
// state machine).
@Suppress("DEPRECATION")
@OptIn(InternalForInheritanceMediampApi::class)
expect class MpvMediampPlayer : MediampPlayer {
    override val impl: Any
    override val state: StateFlow<PlayerState>
    override val events: SharedFlow<PlaybackEvent>
    override val mediaData: StateFlow<MediaData?>
    override val mediaProperties: StateFlow<MediaProperties?>
    override val currentPositionMillis: StateFlow<Long>
    override val playbackProgress: Flow<Float>
    override val features: PlayerFeatures
    override val mainDispatcher: CoroutineDispatcher

    override suspend fun setMediaData(data: MediaData, playWhenReady: Boolean, startPositionMillis: Long)
    override fun play()
    override fun pause()
    override fun stopPlayback()
    override fun seekTo(positionMillis: Long)
    override fun close()

    @Deprecated("Use state instead. See docs/playback-state-v2.md for the mapping.")
    override val playbackState: StateFlow<org.openani.mediamp.PlaybackState>
}
