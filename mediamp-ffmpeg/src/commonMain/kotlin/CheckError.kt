/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

public inline fun Int.checkError(context: String = ""): Int {
    if (this < 0) {
        val msg = if (context.isEmpty()) "FFmpeg error $this" else "FFmpeg error $this ($context)"
        throw FFmpegException(this, msg)
    }
    return this
}
