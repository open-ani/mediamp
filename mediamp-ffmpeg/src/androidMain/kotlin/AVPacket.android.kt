/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import org.bytedeco.ffmpeg.avcodec.AVPacket as NativeAVPacket
import org.bytedeco.ffmpeg.global.avcodec.*

public actual class AVPacket : AutoCloseable {
    internal val native: NativeAVPacket = av_packet_alloc()
        ?: throw FFmpegException(-12)

    actual override fun close() {
        av_packet_free(native)
    }

    public actual fun unref() {
        av_packet_unref(native)
    }

    public actual fun streamIndex(): Int = native.stream_index()
}
