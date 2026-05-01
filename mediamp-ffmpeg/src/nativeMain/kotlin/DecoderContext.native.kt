/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.cinterop.cValuesOf
import org.openani.mediamp.ffmpeg.ffi.avcodec_alloc_context3
import org.openani.mediamp.ffmpeg.ffi.avcodec_find_decoder
import org.openani.mediamp.ffmpeg.ffi.avcodec_free_context
import org.openani.mediamp.ffmpeg.ffi.avcodec_open2
import org.openani.mediamp.ffmpeg.ffi.avcodec_receive_frame
import org.openani.mediamp.ffmpeg.ffi.avcodec_send_packet
import org.openani.mediamp.ffmpeg.internal.NativeAVCodecContext

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
public actual class DecoderContext : AutoCloseable {
    private var native: NativeAVCodecContext? = null

    public actual fun open(codecId: Int) {
        val codec = avcodec_find_decoder(codecId.toUInt())
            ?: throw FFmpegException(-22)
        val ctx = avcodec_alloc_context3(codec) ?: throw FFmpegException(-12)
        native = NativeAVCodecContext(ctx)
        avcodec_open2(ctx, codec, null).checkError()
    }

    public actual fun sendPacket(packet: AVPacket?): Int {
        val ctx = native ?: error("DecoderContext not opened")
        return avcodec_send_packet(ctx.ptr, packet?.native?.ptr)
    }

    public actual fun receiveFrame(frame: AVFrame): Int {
        val ctx = native ?: error("DecoderContext not opened")
        return avcodec_receive_frame(ctx.ptr, frame.native.ptr)
    }

    actual override fun close() {
        native?.let {
            avcodec_free_context(cValuesOf(it.ptr))
            native = null
        }
    }
}
