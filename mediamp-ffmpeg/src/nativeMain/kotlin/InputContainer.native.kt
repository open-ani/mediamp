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
import org.openani.mediamp.ffmpeg.ffi.AVFormatContext
import org.openani.mediamp.ffmpeg.ffi.avformat_close_input
import org.openani.mediamp.ffmpeg.ffi.avformat_find_stream_info
import org.openani.mediamp.ffmpeg.ffi.avformat_open_input
import org.openani.mediamp.ffmpeg.ffi.av_read_frame
import org.openani.mediamp.ffmpeg.ffi.mediamp_format_stream
import org.openani.mediamp.ffmpeg.ffi.mediamp_format_stream_count
import org.openani.mediamp.ffmpeg.internal.NativeAVFormatContext
import org.openani.mediamp.ffmpeg.internal.NativeAVStream

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
public actual class InputContainer : AutoCloseable {
    internal var native: NativeAVFormatContext? = null

    public actual fun open(url: String) {
        memScoped {
            val ptr = alloc<CPointerVar<AVFormatContext>>()
            avformat_open_input(ptr.ptr, url, null, null).checkError()
            native = NativeAVFormatContext(ptr.value!!)
        }
    }

    public actual fun findStreamInfo(): Int {
        val ctx = native ?: error("InputContainer not opened")
        return avformat_find_stream_info(ctx.ptr, null).checkError()
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
