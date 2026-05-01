/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

/**
 * A rational number represented as numerator / denominator.
 *
 * This is a Kotlin counterpart to FFmpeg's `AVRational`.
 */
public data class Rational(
    public val num: Int,
    public val den: Int,
) {
    public fun toDouble(): Double = if (den == 0) 0.0 else num.toDouble() / den.toDouble()

    public companion object {
        public val ZERO: Rational = Rational(0, 1)
    }
}
