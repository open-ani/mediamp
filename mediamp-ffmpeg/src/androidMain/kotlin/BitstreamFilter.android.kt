/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import org.bytedeco.ffmpeg.avcodec.AVBSFContext
import org.bytedeco.ffmpeg.global.avcodec.*

public actual class BitstreamFilter actual constructor(
    name: String,
    stream: Stream,
) : AutoCloseable {
    internal val native: AVBSFContext

    init {
        val filter = av_bsf_get_by_name(name)
            ?: throw FFmpegException(-22)
        val ctx = AVBSFContext()
        av_bsf_alloc(filter, ctx).checkError()
        avcodec_parameters_copy(ctx.par_in(), stream.native.codecpar()).checkError()
        av_bsf_init(ctx).checkError()
        native = ctx
    }

    public actual fun sendPacket(packet: AVPacket) {
        av_bsf_send_packet(native, packet.native).checkError()
    }

    public actual fun receivePacket(packet: AVPacket): Boolean {
        val ret = av_bsf_receive_packet(native, packet.native)
        return when {
            ret == 0 -> true
            ret == AVERROR_EAGAIN || ret == AVERROR_EOF -> false
            else -> throw FFmpegException(ret)
        }
    }

    actual override fun close() {
        av_bsf_free(native)
    }
}
