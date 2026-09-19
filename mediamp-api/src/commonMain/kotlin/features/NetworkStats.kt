/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
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
 * An optional feature of the [org.openani.mediamp.MediampPlayer] that reports network transfer
 * statistics of the media being played.
 */
@ExperimentalMediampApi
@SubclassOptInRequired(InternalForInheritanceMediampApi::class)
public interface NetworkStats : Feature {
    /**
     * A flow of the rate, in bytes per second, at which media data is currently being received
     * from the network. `0` means the network is idle (for example the whole media is already
     * buffered).
     *
     * Emits [UNKNOWN_SPEED] when no media is open, the media is not loaded over the network
     * (for example a local file), or the engine can not measure it. The value is reset to
     * [UNKNOWN_SPEED] whenever media is opened or stopped.
     */
    public val downloadSpeedBytesPerSecond: Flow<Long>

    public companion object Key : FeatureKey<NetworkStats> {
        /**
         * Value of [downloadSpeedBytesPerSecond] meaning the download speed is not known.
         */
        public const val UNKNOWN_SPEED: Long = -1L
    }
}
