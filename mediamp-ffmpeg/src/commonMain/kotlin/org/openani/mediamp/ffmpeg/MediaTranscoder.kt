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
        InputContainer().use { input ->
            input.open(op.input)
            input.findStreamInfo()

            OutputContainer().use { output ->
                output.open(op.output)

                val streamMap = input.streams.map { output.addStream(it) }

                output.writeHeader()

                AVPacket().use { packet ->
                    while (true) {
                        val ret = input.readPacket(packet)
                        if (ret < 0) break
                        val inStream = input.streams[packet.streamIndex()]
                        val outStream = streamMap[inStream.index]
                        avPacketRescaleTs(packet, inStream.timeBase, outStream.timeBase)
                        output.mux(packet, outStream)
                        packet.unref()
                    }
                }
            }
        }
        return FFmpegResult(exitCode = 0)
    }

    private fun executeTranscode(op: MediaOperation.Transcode): FFmpegResult {
        throw UnsupportedOperationException("Transcode not yet implemented via libav* wrapper. Use subprocess fallback.")
    }

    private fun executeProbe(op: MediaOperation.Probe): FFmpegResult {
        InputContainer().use { input ->
            input.open(op.input)
            input.findStreamInfo()
        }
        return FFmpegResult(exitCode = 0)
    }
}
