/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.core.features

import kotlinx.coroutines.flow.Flow

interface PlaybackSpeed : Feature {
    /**
     * A cold flow of the current playback speed. `1.0` by default.
     *
     * `1.0` is the original speed, `2.0` is double speed, `0.5` is half speed, etc.
     */
    val valueFlow: Flow<Float>

    /**
     * The current playback speed.
     */
    val value: Float

    /**
     * Sets the playback speed to [speed].
     *
     * Playback speed settings will continue to be in effect even if the video has been switched.
     */
    fun set(speed: Float)

    companion object Key : FeatureKey<PlaybackSpeed>
}
