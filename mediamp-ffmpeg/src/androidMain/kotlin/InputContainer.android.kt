/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.PointerPointer

public actual class InputContainer : AutoCloseable {
    internal var native: AVFormatContext? = null

    public actual fun open(url: String, options: Map<String, String>) {
        val ctx = avformat_alloc_context() ?: throw FFmpegException(-12)
        val dict: AVDictionary? = options.takeIf { it.isNotEmpty() }?.let { opts ->
            val d = AVDictionary()
            opts.forEach { (k, v) ->
                av_dict_set(d, k, v, 0).checkError()
            }
            d
        }
        avformat_open_input(ctx, url, null, dict).checkError("avformat_open_input: url=$url")
        dict?.let { av_dict_free(it) }
        native = ctx
    }

    public actual fun findStreamInfo(): Int {
        val ctx = native ?: error("InputContainer not opened")
        return avformat_find_stream_info(ctx, null as PointerPointer<*>?).checkError("avformat_find_stream_info")
    }

    public actual val streams: List<Stream>
        get() {
            val ctx = native ?: error("InputContainer not opened")
            val count = ctx.nb_streams()
            return List(count) { idx ->
                Stream(ctx.streams(idx))
            }
        }

    public actual fun readPacket(packet: AVPacket): Int {
        val ctx = native ?: error("InputContainer not opened")
        return av_read_frame(ctx, packet.native)
    }

    actual override fun close() {
        native?.let {
            avformat_close_input(it)
            native = null
        }
    }
}
