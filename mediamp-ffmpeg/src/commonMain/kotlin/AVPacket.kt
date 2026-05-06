/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

public expect class AVPacket() : AutoCloseable {
    public override fun close()
    public fun unref()
    public fun streamIndex(): Int

    /** Presentation timestamp in [Stream.timeBase] units. [NOPTS] if not set. */
    public var pts: Long
    /** Decompression timestamp in [Stream.timeBase] units. [NOPTS] if not set. */
    public var dts: Long

    public companion object {
        public val NOPTS: Long
    }
}
