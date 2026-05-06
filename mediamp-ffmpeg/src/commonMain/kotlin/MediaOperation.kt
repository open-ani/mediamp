/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

public sealed class MediaOperation {
    public data class Remux(
        public val input: String,
        public val output: String,
        public val bitstreamFilters: Map<Int, String> = emptyMap(),
        public val movflags: List<String> = emptyList(),
        public val allowedExtensions: String? = null,
        public val protocolWhitelist: String? = null,
        public val ignoreDts: Boolean = false,
    ) : MediaOperation()

    public data class Transcode(
        public val input: String,
        public val output: String,
        public val videoCodec: String? = null,
        public val audioCodec: String? = null,
    ) : MediaOperation()

    public data class Probe(
        public val input: String,
    ) : MediaOperation()
}
