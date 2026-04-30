/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

public expect class MediaInput() : AutoCloseable {
    public fun open(url: String)
    public fun findStreamInfo(): Int
    public val streamCount: Int
    public fun codecType(streamIndex: Int): Int
    public fun codecId(streamIndex: Int): Int
    public fun readPacket(packet: AVPacket): Int
    public override fun close()
}
