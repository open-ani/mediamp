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
        avformat_alloc_output_context2(ctx, null as AVOutputFormat?, formatName, filename).checkError("avformat_alloc_output_context2: filename=$filename")
        native = ctx
    }

    public actual fun addStream(stream: Stream): Stream {
        val outCtx = native ?: error("OutputContainer not opened")
        val outStream = avformat_new_stream(outCtx, null)
            ?: throw FFmpegException(-12)
        avcodec_parameters_copy(outStream.codecpar(), stream.native.codecpar()).checkError("avcodec_parameters_copy: stream=${stream.index}")
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
            avio_open(pb, ctx.url().string, AVIO_FLAG_WRITE).checkError("avio_open: url=${ctx.url().string}")
            ctx.pb(pb)
        }
        val dict: AVDictionary? = options?.let { opts ->
            val d = AVDictionary()
            opts.forEach { (k, v) ->
                av_dict_set(d, k, v, 0).checkError("av_dict_set: $k=$v")
            }
            d
        }
        avformat_write_header(ctx, dict).checkError("avformat_write_header")
        dict?.let { av_dict_free(it) }
        headerWritten = true
    }

    public actual fun mux(packet: AVPacket, stream: Stream) {
        val ctx = native ?: error("OutputContainer not opened")
        packet.native.stream_index(stream.index)
        av_interleaved_write_frame(ctx, packet.native).checkError("av_interleaved_write_frame: stream=${stream.index}")
    }

    actual override fun close() {
        native?.let { ctx ->
            if (headerWritten) {
                av_write_trailer(ctx)
            }
            if (ctx.pb() != null && (ctx.oformat().flags() and AVFMT_NOFILE) == 0) {
                avio_close(ctx.pb()).checkError("avio_close")
            }
            avformat_free_context(ctx)
            native = null
            headerWritten = false
        }
    }
}
