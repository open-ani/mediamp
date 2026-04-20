/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

/**
 * Result of an FFmpeg command execution.
 */
public class FFmpegResult(
    public val exitCode: Int,
) {
    public val isSuccess: Boolean get() = exitCode == 0

    override fun toString(): String =
        "FFmpegResult(exitCode=$exitCode)"
}

/**
 * A parsed FFmpeg log line emitted by `av_log`.
 */
public class FFmpegLogMessage(
    public val level: Int,
    public val line: String,
) {
    public val isError: Boolean get() = level <= 24
}

public fun interface FFmpegLogHandler {
    public fun onLog(message: FFmpegLogMessage)
}

internal class FFmpegLogLineCollector(
    private val dispatch: (FFmpegLogMessage) -> Unit,
) {
    private val buffer: StringBuilder = StringBuilder()
    private var bufferedLevel: Int = 32

    fun append(level: Int, chunk: String) {
        if (chunk.isEmpty()) return
        if (buffer.isEmpty()) {
            bufferedLevel = level
        }
        buffer.append(chunk)
        emitCompletedLines()
    }

    fun flush() {
        if (buffer.isNotEmpty()) {
            dispatch(FFmpegLogMessage(bufferedLevel, buffer.toString()))
            buffer.clear()
        }
    }

    private fun emitCompletedLines() {
        while (true) {
            val delimiterIndex = nextDelimiterIndex() ?: return
            val delimiterLength = delimiterLengthAt(delimiterIndex)
            val line = buffer.substring(0, delimiterIndex)
            dispatch(FFmpegLogMessage(bufferedLevel, line))
            val remaining = buffer.substring(delimiterIndex + delimiterLength)
            buffer.clear()
            buffer.append(remaining)
        }
    }

    private fun nextDelimiterIndex(): Int? {
        for (index in 0 until buffer.length) {
            when (buffer[index]) {
                '\n', '\r' -> return index
            }
        }
        return null
    }

    private fun delimiterLengthAt(index: Int): Int {
        val current = buffer[index]
        return if (current == '\r' && index + 1 < buffer.length && buffer[index + 1] == '\n') {
            2
        } else {
            1
        }
    }
}
