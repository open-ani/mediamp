/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import org.openani.mediamp.ffmpeg.ffi.AVDictionary
import org.openani.mediamp.ffmpeg.ffi.AVFormatContext
import org.openani.mediamp.ffmpeg.ffi.av_dict_free
import org.openani.mediamp.ffmpeg.ffi.av_dict_set
import org.openani.mediamp.ffmpeg.ffi.avformat_alloc_output_context2
import org.openani.mediamp.ffmpeg.ffi.avformat_write_header
import org.openani.mediamp.ffmpeg.ffi.av_interleaved_write_frame
import org.openani.mediamp.ffmpeg.ffi.av_write_trailer
import org.openani.mediamp.ffmpeg.ffi.mediamp_avio_open
import org.openani.mediamp.ffmpeg.ffi.mediamp_close_output
import org.openani.mediamp.ffmpeg.ffi.mediamp_copy_codec_params
import org.openani.mediamp.ffmpeg.ffi.mediamp_new_stream
import org.openani.mediamp.ffmpeg.ffi.mediamp_packet_set_stream_index
import org.openani.mediamp.ffmpeg.internal.NativeAVFormatContext
import org.openani.mediamp.ffmpeg.internal.NativeAVStream

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
public actual class OutputContainer : AutoCloseable {
    internal var native: NativeAVFormatContext? = null

    public actual fun open(filename: String, formatName: String?) {
        memScoped {
            val ptr = alloc<CPointerVar<AVFormatContext>>()
            avformat_alloc_output_context2(
                ctx = ptr.ptr,
                oformat = null,
                format_name = formatName,
                filename = filename,
            ).checkError()
            native = NativeAVFormatContext(ptr.value!!)
        }
    }

    public actual fun addStream(stream: Stream): Stream {
        val outCtx = native ?: error("OutputContainer not opened")
        val outStream = mediamp_new_stream(outCtx.ptr)
            ?: throw FFmpegException(-12)
        mediamp_copy_codec_params(outStream, stream.native.ptr).checkError()
        outStream.pointed.time_base.num = stream.native.ptr.pointed.time_base.num
        outStream.pointed.time_base.den = stream.native.ptr.pointed.time_base.den
        return Stream(NativeAVStream(outStream))
    }

    public actual fun writeHeader(options: MuxerOptions?) {
        val ctx = native ?: error("OutputContainer not opened")
        val ret = mediamp_avio_open(ctx.ptr)
        if (ret < 0) throw FFmpegException(ret)
        memScoped {
            val dictVar = alloc<CPointerVar<AVDictionary>>()
            options?.forEach { (k, v) ->
                av_dict_set(dictVar.ptr, k, v, 0).checkError()
            }
            avformat_write_header(ctx.ptr, dictVar.ptr).checkError()
            dictVar.value?.let { av_dict_free(cValuesOf(it)) }
        }
    }

    public actual fun mux(packet: AVPacket, stream: Stream) {
        val ctx = native ?: error("OutputContainer not opened")
        mediamp_packet_set_stream_index(packet.native.ptr, stream.index)
        av_interleaved_write_frame(ctx.ptr, packet.native.ptr).checkError()
    }

    actual override fun close() {
        native?.let {
            av_write_trailer(it.ptr)
            mediamp_close_output(it.ptr)
            native = null
        }
    }
}
