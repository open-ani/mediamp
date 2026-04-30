/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame as NativeAVFrame
import org.bytedeco.ffmpeg.avcodec.AVPacket as NativeAVPacket
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.PointerPointer

public actual class MediaInput : AutoCloseable {
    private var native: AVFormatContext? = null

    public actual fun open(url: String) {
        val ctx = avformat_alloc_context() ?: throw FFmpegException(-12)
        avformat_open_input(ctx, url, null, null).checkError()
        native = ctx
    }

    public actual fun findStreamInfo(): Int {
        val ctx = native ?: error("MediaInput not opened")
        return avformat_find_stream_info(ctx, null as PointerPointer<*>?).checkError()
    }

    public actual val streamCount: Int
        get() = native?.nb_streams() ?: 0

    public actual fun codecType(streamIndex: Int): Int {
        val ctx = native ?: error("MediaInput not opened")
        val stream = ctx.streams(streamIndex) ?: throw IndexOutOfBoundsException("Stream index $streamIndex out of bounds")
        return stream.codecpar().codec_type()
    }

    public actual fun codecId(streamIndex: Int): Int {
        val ctx = native ?: error("MediaInput not opened")
        val stream = ctx.streams(streamIndex) ?: throw IndexOutOfBoundsException("Stream index $streamIndex out of bounds")
        return stream.codecpar().codec_id()
    }

    public actual fun readPacket(packet: AVPacket): Int {
        val ctx = native ?: error("MediaInput not opened")
        return av_read_frame(ctx, packet.native)
    }

    actual override fun close() {
        native?.let {
            avformat_close_input(it)
            native = null
        }
    }
}

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

public actual class AVPacket : AutoCloseable {
    internal val native: NativeAVPacket = av_packet_alloc()
        ?: throw FFmpegException(-12)

    actual override fun close() {
        av_packet_free(native)
    }

    public actual fun unref() {
        av_packet_unref(native)
    }
}

public actual class AVFrame : AutoCloseable {
    internal val native: NativeAVFrame = av_frame_alloc()
        ?: throw FFmpegException(-12)

    actual override fun close() {
        av_frame_free(native)
    }
}
