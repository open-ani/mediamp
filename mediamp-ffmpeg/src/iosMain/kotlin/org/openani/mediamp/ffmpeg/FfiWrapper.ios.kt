/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import org.openani.mediamp.ffmpeg.ffi.AVCodecContext
import org.openani.mediamp.ffmpeg.ffi.AVFormatContext
import org.openani.mediamp.ffmpeg.ffi.AVFrame as NativeAVFrame
import org.openani.mediamp.ffmpeg.ffi.AVPacket as NativeAVPacket
import org.openani.mediamp.ffmpeg.ffi.av_packet_alloc
import org.openani.mediamp.ffmpeg.ffi.av_packet_free
import org.openani.mediamp.ffmpeg.ffi.av_packet_unref
import org.openani.mediamp.ffmpeg.ffi.av_frame_alloc
import org.openani.mediamp.ffmpeg.ffi.av_frame_free
import org.openani.mediamp.ffmpeg.ffi.av_read_frame
import org.openani.mediamp.ffmpeg.ffi.avcodec_alloc_context3
import org.openani.mediamp.ffmpeg.ffi.avcodec_find_decoder
import org.openani.mediamp.ffmpeg.ffi.avcodec_free_context
import org.openani.mediamp.ffmpeg.ffi.avcodec_open2
import org.openani.mediamp.ffmpeg.ffi.avcodec_receive_frame
import org.openani.mediamp.ffmpeg.ffi.avcodec_send_packet
import org.openani.mediamp.ffmpeg.ffi.avformat_close_input
import org.openani.mediamp.ffmpeg.ffi.avformat_find_stream_info
import org.openani.mediamp.ffmpeg.ffi.avformat_open_input
import org.openani.mediamp.ffmpeg.ffi.mediamp_format_stream
import org.openani.mediamp.ffmpeg.ffi.mediamp_format_stream_count
import org.openani.mediamp.ffmpeg.ffi.mediamp_stream_codec_id
import org.openani.mediamp.ffmpeg.ffi.mediamp_stream_codec_type

@OptIn(ExperimentalForeignApi::class)
public actual class MediaInput : AutoCloseable {
    private var native: CPointer<AVFormatContext>? = null

    public actual fun open(url: String) {
        memScoped {
            val ptr = alloc<CPointerVar<AVFormatContext>>()
            avformat_open_input(ptr.ptr, url, null, null).checkError()
            native = ptr.value
        }
    }

    public actual fun findStreamInfo(): Int {
        val ctx = native ?: error("MediaInput not opened")
        return avformat_find_stream_info(ctx, null).checkError()
    }

    public actual val streamCount: Int
        get() = native?.let { mediamp_format_stream_count(it) } ?: 0

    public actual fun codecType(streamIndex: Int): Int {
        val ctx = native ?: error("MediaInput not opened")
        val stream = mediamp_format_stream(ctx, streamIndex)
            ?: throw IndexOutOfBoundsException("Stream index $streamIndex out of bounds")
        return mediamp_stream_codec_type(stream)
    }

    public actual fun codecId(streamIndex: Int): Int {
        val ctx = native ?: error("MediaInput not opened")
        val stream = mediamp_format_stream(ctx, streamIndex)
            ?: throw IndexOutOfBoundsException("Stream index $streamIndex out of bounds")
        return mediamp_stream_codec_id(stream)
    }

    public actual fun readPacket(packet: AVPacket): Int {
        val ctx = native ?: error("MediaInput not opened")
        return av_read_frame(ctx, packet.native)
    }

    actual override fun close() {
        native?.let {
            avformat_close_input(cValuesOf(it))
            native = null
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
public actual class DecoderContext : AutoCloseable {
    private var native: CPointer<AVCodecContext>? = null

    public actual fun open(codecId: Int) {
        val codec = avcodec_find_decoder(codecId.toUInt())
            ?: throw FFmpegException(-22) // AVERROR(EINVAL)
        val ctx = avcodec_alloc_context3(codec)
            ?: throw FFmpegException(-12) // AVERROR(ENOMEM)
        native = ctx
        avcodec_open2(ctx, codec, null).checkError()
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
            avcodec_free_context(cValuesOf(it))
            native = null
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
public actual class AVPacket : AutoCloseable {
    internal val native: CPointer<NativeAVPacket> = av_packet_alloc()
        ?: throw FFmpegException(-12)

    actual override fun close() {
        av_packet_free(cValuesOf(native))
    }

    public actual fun unref() {
        av_packet_unref(native)
    }
}

@OptIn(ExperimentalForeignApi::class)
public actual class AVFrame : AutoCloseable {
    internal val native: CPointer<NativeAVFrame> = av_frame_alloc()
        ?: throw FFmpegException(-12)

    actual override fun close() {
        av_frame_free(cValuesOf(native))
    }
}
