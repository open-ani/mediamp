/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.features

import kotlinx.coroutines.flow.StateFlow

/**
 * An optional feature of the [org.openani.mediamp.core.MediampPlayer]
 * that allows controlling the output audio volume and mute state.
 */
public interface AudioLevelController : Feature {
    /**
     * A hot flow of the current volume level in the range of `0.0` to [maxVolume].
     *
     * `1.0` is the original volume level.
     */
    public val volume: StateFlow<Float>

    /**
     * The maximum volume level that is supported by the implementation. Typically `1.0` (original) or `2.0` (i.e. amplification).
     */
    public val maxVolume: Float

    /**
     * A hot flow of the current mute state, where `true` means the audio is muted.
     * When the audio is muted, it is not guaranteed that [volume] will emit `0.0`.
     */
    public val isMute: StateFlow<Boolean>

    /**
     * Sets the mute state of the audio.
     * @param mute `true` to mute audio, `false` to unmute.
     */
    public fun setMute(mute: Boolean)

    /**
     * Sets the volume level to [volume].
     *
     * Volume will be coerced in the range of `0.0` to [maxVolume],
     * so a value that is out of range will not cause an exception.
     */
    public fun setVolume(volume: Float)

    /**
     * Increases the volume by [value] (default is 0.05).
     * The resulting volume will be coerced to the range of `0.0` to [maxVolume].
     */
    public fun volumeUp(value: Float = 0.05f)

    /**
     * Decreases the volume by [value] (default is 0.05).
     * The resulting volume will be coerced to the range of `0.0` to [maxVolume].
     */
    public fun volumeDown(value: Float = 0.05f)

    public companion object Key : FeatureKey<AudioLevelController>
}