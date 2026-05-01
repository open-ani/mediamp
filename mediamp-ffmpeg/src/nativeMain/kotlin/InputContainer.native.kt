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
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import org.openani.mediamp.ffmpeg.ffi.AVDictionary
import org.openani.mediamp.ffmpeg.ffi.AVFormatContext
import org.openani.mediamp.ffmpeg.ffi.av_dict_free
import org.openani.mediamp.ffmpeg.ffi.av_dict_set
import org.openani.mediamp.ffmpeg.ffi.avformat_close_input
import org.openani.mediamp.ffmpeg.ffi.avformat_find_stream_info
import org.openani.mediamp.ffmpeg.ffi.avformat_open_input
import org.openani.mediamp.ffmpeg.ffi.av_read_frame
import org.openani.mediamp.ffmpeg.ffi.mediamp_avfmt_flag_igndts
import org.openani.mediamp.ffmpeg.ffi.mediamp_format_stream
import org.openani.mediamp.ffmpeg.ffi.mediamp_format_stream_count
import org.openani.mediamp.ffmpeg.ffi.mediamp_set_fmt_flags
import org.openani.mediamp.ffmpeg.internal.NativeAVFormatContext
import org.openani.mediamp.ffmpeg.internal.NativeAVStream

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
public actual class InputContainer : AutoCloseable {
    internal var native: NativeAVFormatContext? = null

    public actual fun open(url: String, options: Map<String, String>, ignoreDts: Boolean) {
        memScoped {
            val ptr = alloc<CPointerVar<AVFormatContext>>()
            val dictVar = alloc<CPointerVar<AVDictionary>>()
            options.forEach { (k, v) ->
                av_dict_set(dictVar.ptr, k, v, 0).checkError()
            }
            avformat_open_input(ptr.ptr, url, null, dictVar.ptr).checkError("avformat_open_input: url=$url")
            dictVar.value?.let { av_dict_free(cValuesOf(it)) }
            val ctx = ptr.value!!
            if (ignoreDts) {
                mediamp_set_fmt_flags(ctx, mediamp_avfmt_flag_igndts())
            }
            native = NativeAVFormatContext(ctx)
        }
    }

    public actual fun findStreamInfo(): Int {
        val ctx = native ?: error("InputContainer not opened")
        return avformat_find_stream_info(ctx.ptr, null).checkError("avformat_find_stream_info")
    }

    public actual val streams: List<Stream>
        get() {
            val ctx = native ?: error("InputContainer not opened")
            val count = mediamp_format_stream_count(ctx.ptr)
            return List(count) { idx ->
                Stream(NativeAVStream(mediamp_format_stream(ctx.ptr, idx)!!))
            }
        }

    public actual fun readPacket(packet: AVPacket): Int {
        val ctx = native ?: error("InputContainer not opened")
        return av_read_frame(ctx.ptr, packet.native.ptr)
    }

    actual override fun close() {
        native?.let {
            avformat_close_input(cValuesOf(it.ptr))
            native = null
        }
    }
}
