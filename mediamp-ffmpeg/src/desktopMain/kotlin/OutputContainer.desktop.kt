/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVOutputFormat
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*

public actual class OutputContainer : AutoCloseable {
    internal var native: AVFormatContext? = null
    private var headerWritten: Boolean = false

    public actual fun open(filename: String, formatName: String?) {
        val ctx = AVFormatContext()
        avformat_alloc_output_context2(ctx, null as AVOutputFormat?, formatName, filename).checkError()
        native = ctx
    }

    public actual fun addStream(stream: Stream): Stream {
        val outCtx = native ?: error("OutputContainer not opened")
        val outStream = avformat_new_stream(outCtx, null)
            ?: throw FFmpegException(-12)
        avcodec_parameters_copy(outStream.codecpar(), stream.native.codecpar()).checkError()
        // Reset codec_tag so the output muxer derives the correct one for its format
        // (e.g. MPEG-TS may have stream_type as tag 27, but MP4 expects 'avc1').
        outStream.codecpar().codec_tag(0)
        outStream.time_base(stream.native.time_base())
        return Stream(outStream)
    }

    public actual fun writeHeader(options: MuxerOptions?) {
        val ctx = native ?: error("OutputContainer not opened")
        if (ctx.pb() == null && (ctx.oformat().flags() and AVFMT_NOFILE) == 0) {
            val pb = AVIOContext()
            avio_open(pb, ctx.url().string, AVIO_FLAG_WRITE).checkError()
            ctx.pb(pb)
        }
        val dict: AVDictionary? = options?.let { opts ->
            val d = AVDictionary()
            opts.forEach { (k, v) ->
                av_dict_set(d, k, v, 0).checkError()
            }
            d
        }
        avformat_write_header(ctx, dict).checkError()
        dict?.let { av_dict_free(it) }
        headerWritten = true
    }

    public actual fun mux(packet: AVPacket, stream: Stream) {
        val ctx = native ?: error("OutputContainer not opened")
        packet.native.stream_index(stream.index)
        av_interleaved_write_frame(ctx, packet.native).checkError()
    }

    actual override fun close() {
        native?.let { ctx ->
            if (headerWritten) {
                val ret = av_write_trailer(ctx)
                if (ret < 0) throw FFmpegException(
                    ret,
                    "av_write_trailer failed (ret=$ret)"
                )
            }
            if (ctx.pb() != null && (ctx.oformat().flags() and AVFMT_NOFILE) == 0) {
                val ret = avio_close(ctx.pb())
                if (ret < 0) throw FFmpegException(
                    ret,
                    "avio_close failed (ret=$ret, pb-error=${ctx.pb().let { it?.error() ?: "null" }}"
                )
            }
            avformat_free_context(ctx)
            native = null
            headerWritten = false
        }
    }
}
