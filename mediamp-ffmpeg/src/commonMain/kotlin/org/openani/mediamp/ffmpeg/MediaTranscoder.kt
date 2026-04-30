/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

public class MediaTranscoder {
    public fun execute(operation: MediaOperation): FFmpegResult {
        return when (operation) {
            is MediaOperation.Remux -> executeRemux(operation)
            is MediaOperation.Transcode -> executeTranscode(operation)
            is MediaOperation.Probe -> executeProbe(operation)
        }
    }

    private fun executeRemux(op: MediaOperation.Remux): FFmpegResult {
        val input = MediaInput()
        try {
            input.open(op.input)
            input.findStreamInfo()

            val output = MediaOutput()
            try {
                output.open(op.output)

                val streamCount = input.streamCount
                val streamMap = IntArray(streamCount) { -1 }
                for (i in 0 until streamCount) {
                    streamMap[i] = output.copyStreamFrom(input, i)
                }

                output.writeHeader()

                val packet = AVPacket()
                try {
                    while (true) {
                        val ret = input.readPacket(packet)
                        if (ret < 0) break
                        val inStreamIndex = packet.streamIndex()
                        val outStreamIndex = streamMap[inStreamIndex]
                        if (outStreamIndex >= 0) {
                            output.writePacket(packet, outStreamIndex)
                        }
                        packet.unref()
                    }
                } finally {
                    packet.close()
                }
            } finally {
                output.close()
            }
        } finally {
            input.close()
        }
        return FFmpegResult(exitCode = 0)
    }

    private fun executeTranscode(op: MediaOperation.Transcode): FFmpegResult {
        throw UnsupportedOperationException("Transcode not yet implemented via libav* wrapper. Use subprocess fallback.")
    }

    private fun executeProbe(op: MediaOperation.Probe): FFmpegResult {
        val input = MediaInput()
        try {
            input.open(op.input)
            input.findStreamInfo()
        } finally {
            input.close()
        }
        return FFmpegResult(exitCode = 0)
    }
}
