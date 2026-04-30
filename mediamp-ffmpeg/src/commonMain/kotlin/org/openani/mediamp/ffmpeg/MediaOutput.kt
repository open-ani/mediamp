/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

public expect class MediaOutput() : AutoCloseable {
    public fun open(filename: String)
    public fun copyStreamFrom(input: MediaInput, streamIndex: Int): Int
    public fun writeHeader()
    public fun writePacket(packet: AVPacket, outStreamIndex: Int)
    public override fun close()
}
