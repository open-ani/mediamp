/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avutil.av_make_q

internal actual fun avPacketRescaleTs(packet: AVPacket, srcTimeBase: Rational, dstTimeBase: Rational) {
    av_packet_rescale_ts(
        packet.native,
        av_make_q(srcTimeBase.num, srcTimeBase.den),
        av_make_q(dstTimeBase.num, dstTimeBase.den),
    )
}
