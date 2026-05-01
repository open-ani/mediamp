/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.cinterop.cValue
import org.openani.mediamp.ffmpeg.ffi.AVRational
import org.openani.mediamp.ffmpeg.ffi.av_packet_rescale_ts

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual fun avPacketRescaleTs(packet: AVPacket, srcTimeBase: Rational, dstTimeBase: Rational) {
    av_packet_rescale_ts(
        packet.native.ptr,
        cValue<AVRational> {
            num = srcTimeBase.num
            den = srcTimeBase.den
        },
        cValue<AVRational> {
            num = dstTimeBase.num
            den = dstTimeBase.den
        },
    )
}
