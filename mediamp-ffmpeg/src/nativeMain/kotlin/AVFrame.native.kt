/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.cinterop.cValuesOf
import org.openani.mediamp.ffmpeg.ffi.av_frame_alloc
import org.openani.mediamp.ffmpeg.ffi.av_frame_free
import org.openani.mediamp.ffmpeg.internal.NativeAVFrame

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
public actual class AVFrame : AutoCloseable {
    internal val native: NativeAVFrame = NativeAVFrame(av_frame_alloc()
        ?: throw FFmpegException(-12))

    actual override fun close() {
        av_frame_free(cValuesOf(native.ptr))
    }
}
