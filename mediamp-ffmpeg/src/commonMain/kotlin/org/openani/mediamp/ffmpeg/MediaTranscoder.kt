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

                // Create bitstream filters for output streams that need them.
                val bsfMap = mutableMapOf<Int, BitstreamFilter>()
                op.bitstreamFilters.forEach { (streamIndex, filterName) ->
                    streamMap.getOrNull(streamIndex)?.let { outStream ->
                        bsfMap[outStream.index] = BitstreamFilter(filterName, outStream)
                    }
                }

                val muxerOptions = MuxerOptions().apply {
                    op.movflags.forEach { movflags(it) }
                }
                output.writeHeader(muxerOptions)

                AVPacket().use { packet ->
                    AVPacket().use { filteredPacket ->
                        while (true) {
                            val ret = input.readPacket(packet)
                            if (ret < 0) break
                            val inStream = input.streams[packet.streamIndex()]
                            val outStream = streamMap[inStream.index]
                            avPacketRescaleTs(packet, inStream.timeBase, outStream.timeBase)

                            val bsf = bsfMap[outStream.index]
                            if (bsf != null) {
                                bsf.sendPacket(packet)
                                while (bsf.receivePacket(filteredPacket)) {
                                    output.mux(filteredPacket, outStream)
                                    filteredPacket.unref()
                                }
                            } else {
                                output.mux(packet, outStream)
                            }
                            packet.unref()
                        }
                    }
                }

                bsfMap.values.forEach { it.close() }
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
