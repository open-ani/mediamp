/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import org.openani.mediamp.MediampPlayerFactory
import org.openani.mediamp.source.MediaData
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/**
 * [MediampPlayerFactory] creating [ExoPlayerMediampPlayer] instances.
 */
public class ExoPlayerMediampPlayerFactory : MediampPlayerFactory<ExoPlayerMediampPlayer> {
    override val forClass: KClass<ExoPlayerMediampPlayer> get() = ExoPlayerMediampPlayer::class

    override fun create(
        context: Any,
        parentCoroutineContext: CoroutineContext,
    ): ExoPlayerMediampPlayer {
        require(context is Context) { "The context argument must be android.content.Context on Android" }
        return create(context, parentCoroutineContext)
    }

    /**
     * Creates a new [ExoPlayerMediampPlayer].
     *
     * @param audioTimeStretch the time-stretch backend used for playback speed changes.
     * @param mediaSourceInterceptor optional per-open hook applied to the built [MediaSource]
     *   before it is set on the player (spec `docs/playback-state-v2.md` §11).
     */
    @OptIn(UnstableApi::class)
    public fun create(
        context: Context,
        parentCoroutineContext: CoroutineContext,
        audioTimeStretch: ExoPlayerAudioTimeStretch = ExoPlayerAudioTimeStretch.Media3Default,
        mediaSourceInterceptor: ((MediaSource, MediaData) -> MediaSource)? = null,
    ): ExoPlayerMediampPlayer {
        return ExoPlayerMediampPlayer(context, parentCoroutineContext, audioTimeStretch, mediaSourceInterceptor)
    }
}
