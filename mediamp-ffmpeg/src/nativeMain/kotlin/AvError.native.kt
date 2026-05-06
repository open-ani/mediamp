/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import org.openani.mediamp.ffmpeg.ffi.mediamp_averrno_eagain

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual val AVERROR_EAGAIN: Int = mediamp_averrno_eagain()
