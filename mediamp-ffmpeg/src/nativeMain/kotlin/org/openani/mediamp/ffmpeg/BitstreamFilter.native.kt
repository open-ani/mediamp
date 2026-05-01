/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.pointed
import org.openani.mediamp.ffmpeg.ffi.AVBSFContext
import org.openani.mediamp.ffmpeg.ffi.mediamp_bsf_free
import org.openani.mediamp.ffmpeg.ffi.mediamp_bsf_init
import org.openani.mediamp.ffmpeg.ffi.mediamp_bsf_receive_packet
import org.openani.mediamp.ffmpeg.ffi.mediamp_bsf_send_packet

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
public actual class BitstreamFilter actual constructor(
    name: String,
    stream: Stream,
) : AutoCloseable {
    internal val native: CPointer<AVBSFContext>

    init {
        val ctx = mediamp_bsf_init(name, stream.native.ptr.pointed.codecpar)
            ?: throw FFmpegException(-22)
        native = ctx
    }

    public actual fun sendPacket(packet: AVPacket) {
        mediamp_bsf_send_packet(native, packet.native.ptr).checkError()
    }

    public actual fun receivePacket(packet: AVPacket): Boolean {
        val ret = mediamp_bsf_receive_packet(native, packet.native.ptr)
        return when {
            ret == 0 -> true
            ret == AVERROR_EAGAIN || ret == AVERROR_EOF -> false
            else -> throw FFmpegException(ret)
        }
    }

    actual override fun close() {
        mediamp_bsf_free(native)
    }
}
