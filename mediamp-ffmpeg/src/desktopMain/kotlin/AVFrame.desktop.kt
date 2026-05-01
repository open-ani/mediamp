/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import org.bytedeco.ffmpeg.avutil.AVFrame as NativeAVFrame
import org.bytedeco.ffmpeg.global.avutil.*

public actual class AVFrame : AutoCloseable {
    internal val native: NativeAVFrame = av_frame_alloc()
        ?: throw FFmpegException(-12)

    actual override fun close() {
        av_frame_free(native)
    }
}
