/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.features

import kotlinx.coroutines.flow.Flow
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.InternalForInheritanceMediampApi

/**
 * An optional feature of the [org.openani.mediamp.MediampPlayer] that allows retrieving buffering information.
 */
@ExperimentalMediampApi
@SubclassOptInRequired(InternalForInheritanceMediampApi::class)
public interface Buffering : Feature {
    /**
     * A flow of the buffering state.
     *
     * The buffering axis moved into the core state model; this flow is a view of it.
     */
    @Deprecated(
        "Buffering is part of the core state now. Use player.state.map { it.isBuffering }.",
        ReplaceWith("player.state.map { it.isBuffering }"),
    )
    public val isBuffering: Flow<Boolean>

    /**
     * A flow of the buffering percentage, where `0` means nothing has already been buffered (playing is not possible),
     * and `100` means the video is fully buffered (seeking to anywhere is possible).
     */
    public val bufferedPercentage: Flow<Int>

    /**
     * A flow of the media position, in milliseconds on the media timeline, up to which data is
     * contiguously buffered ahead of the playhead. Data behind the playhead may have been evicted.
     *
     * Emits [UNKNOWN_POSITION] when no media is open or the engine can not report it. The value is
     * reset to [UNKNOWN_POSITION] whenever media is opened or stopped.
     */
    public val bufferedPositionMillis: Flow<Long>

    public companion object Key : FeatureKey<Buffering> {
        /**
         * Value of [bufferedPositionMillis] meaning the buffered position is not known.
         */
        public const val UNKNOWN_POSITION: Long = -1L
    }
}
