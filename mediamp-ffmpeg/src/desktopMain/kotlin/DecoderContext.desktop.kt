/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec.*

public actual class DecoderContext : AutoCloseable {
    private var native: AVCodecContext? = null

    public actual fun open(codecId: Int) {
        val codec = avcodec_find_decoder(codecId)
            ?: throw FFmpegException(-22)
        val ctx = avcodec_alloc_context3(codec) ?: throw FFmpegException(-12)
        native = ctx
        avcodec_open2(ctx, codec, null as AVDictionary?).checkError()
    }

    public actual fun sendPacket(packet: AVPacket?): Int {
        val ctx = native ?: error("DecoderContext not opened")
        return avcodec_send_packet(ctx, packet?.native)
    }

    public actual fun receiveFrame(frame: AVFrame): Int {
        val ctx = native ?: error("DecoderContext not opened")
        return avcodec_receive_frame(ctx, frame.native)
    }

    actual override fun close() {
        native?.let {
            avcodec_free_context(it)
            native = null
        }
    }
}
