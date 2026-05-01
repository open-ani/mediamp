/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

/**
 * A mutable map of muxer options that can be passed to [OutputContainer.writeHeader].
 *
 * This is a Kotlin counterpart to FFmpeg's `AVDictionary` used for muxer-specific
 * options such as `-movflags +faststart`.
 */
public class MuxerOptions private constructor(
    private val map: MutableMap<String, String>,
) : MutableMap<String, String> by map {
    public constructor() : this(mutableMapOf())

    /**
     * Set the `movflags` option for MP4/MOV muxers.
     *
     * Example: `movflags("faststart")` sets `movflags=+faststart`.
     */
    public fun movflags(vararg flags: String) {
        map["movflags"] = flags.joinToString("+") { it.removePrefix("+") }
    }
}
