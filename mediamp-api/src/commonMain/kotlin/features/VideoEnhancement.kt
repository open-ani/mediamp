/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.features

import kotlinx.coroutines.flow.StateFlow
import org.openani.mediamp.InternalForInheritanceMediampApi

/** Video enhancement modes supported by a player backend. */
public enum class VideoEnhancementMode {
    /** Keep the backend's original video rendering behavior. */
    OFF,

    /** Use the backend's high-quality viewport upscaling path when upscaling is needed. */
    CLEAR,
}

/**
 * An optional feature for selecting the video enhancement mode of the current player.
 */
@SubclassOptInRequired(InternalForInheritanceMediampApi::class)
public interface VideoEnhancement : Feature {
    /** The mode requested for this player instance. */
    public val mode: StateFlow<VideoEnhancementMode>

    /** Modes implemented by this backend. */
    public val supportedModes: Set<VideoEnhancementMode>

    /** Selects [mode] without rebuilding the player or changing playback position. */
    public fun setMode(mode: VideoEnhancementMode)

    public companion object Key : FeatureKey<VideoEnhancement>
}
