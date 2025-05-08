/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.cef

import kotlinx.coroutines.flow.StateFlow
import org.openani.mediamp.AbstractMediampPlayer
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.source.MediaData
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(InternalMediampApi::class)
public class CefMediampPlayer(
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
) : AbstractMediampPlayer<AbstractMediampPlayer.Data>(
    parentCoroutineContext,
) {
    override fun stopPlaybackImpl() {
        TODO("Not yet implemented")
    }

    override suspend fun startPlayer(data: AbstractMediampPlayer.Data) {
        TODO("Not yet implemented")
    }

    override suspend fun setDataImpl(data: MediaData): AbstractMediampPlayer.Data {
        TODO("Not yet implemented")
    }

    override fun closeImpl() {
        TODO("Not yet implemented")
    }

    override val impl: Any
        get() = TODO("Not yet implemented")
    override val mediaProperties: StateFlow<MediaProperties?>
        get() = TODO("Not yet implemented")

    override fun getCurrentMediaProperties(): MediaProperties? {
        TODO("Not yet implemented")
    }

    override val currentPositionMillis: StateFlow<Long>
        get() = TODO("Not yet implemented")
    override val features: PlayerFeatures
        get() = TODO("Not yet implemented")

    override fun getCurrentPlaybackState(): PlaybackState {
        TODO("Not yet implemented")
    }

    override fun getCurrentPositionMillis(): Long {
        TODO("Not yet implemented")
    }

    override fun resume() {
        TODO("Not yet implemented")
    }

    override fun pause() {
        TODO("Not yet implemented")
    }

    override fun seekTo(positionMillis: Long) {
        TODO("Not yet implemented")
    }

    private class Data(
        mediaData: org.openani.mediamp.source.MediaData, releaseResource: () -> Unit
    ) : AbstractMediampPlayer.Data(mediaData, releaseResource)
}
