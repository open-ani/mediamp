/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer

/**
 * Audio time-stretch backend used by [ExoPlayerMediampPlayer] for playback speed changes.
 *
 * This is a creation-time option; it cannot be changed after the player is created.
 */
public enum class ExoPlayerAudioTimeStretch {
    /**
     * Media3 default behavior: [androidx.media3.common.audio.SonicAudioProcessor] via the default
     * audio processor chain. This is what ExoPlayer does out of the box.
     */
    Media3Default,

    /**
     * High-quality WSOLA time-stretch implemented in a native library (`libmediamp_wsola`).
     *
     * If the native library cannot be loaded at runtime, or the input audio format is not
     * supported (WSOLA accepts mono/stereo 16-bit PCM and PCM float), the player transparently
     * falls back to [androidx.media3.common.audio.SonicAudioProcessor].
     */
    HighQualityWsola,
}
