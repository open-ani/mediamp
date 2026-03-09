/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.coroutines.flow.Flow

/**
 * Result of an FFmpeg command execution.
 */
public class FFmpegResult(
    public val exitCode: Int,
    public val stdout: String,
    public val stderr: String,
) {
    public val isSuccess: Boolean get() = exitCode == 0

    override fun toString(): String =
        "FFmpegResult(exitCode=$exitCode, stdout=${stdout.take(200)}, stderr=${stderr.take(200)})"
}

/**
 * A line of output from an FFmpeg process.
 */
public class FFmpegOutputLine(
    public val line: String,
    public val isError: Boolean,
)
