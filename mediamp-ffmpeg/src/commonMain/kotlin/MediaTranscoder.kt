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
            val inputOptions = buildMap {
                op.allowedExtensions?.let { put("allowed_extensions", it) }
                op.protocolWhitelist?.let { put("protocol_whitelist", it) }
            }
            input.open(op.input, inputOptions, op.ignoreDts)
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
                        // Track per-output-stream DTS offsets to handle HLS segment discontinuities.
                        val dtsOffsets = mutableMapOf<Int, Long>()   // outStream.index -> offset
                        val lastDts = mutableMapOf<Int, Long>()      // outStream.index -> last dts

                        while (true) {
                            val ret = input.readPacket(packet)
                            when {
                                ret == 0 -> { /* success, continue below */ }
                                ret == AVERROR_EOF -> break
                                ret == AVERROR_EAGAIN -> continue
                                ret < 0 -> throw FFmpegException(
                                    ret,
                                    "input.readPacket failed (ret=$ret, stream=${input.streams.map { it.index to it.codecType }})"
                                )
                                else -> break
                            }
                            val inStream = input.streams[packet.streamIndex()]
                            val outStream = streamMap[inStream.index]
                            avPacketRescaleTs(packet, inStream.timeBase, outStream.timeBase)

                            // Fix non-monotonic DTS caused by HLS segment discontinuities.
                            val pts = packet.pts
                            val dts = packet.dts
                            if (dts != AVPacket.NOPTS) {
                                val offset = dtsOffsets[outStream.index] ?: 0L
                                val adjustedDts = dts + offset
                                val prevDts = lastDts[outStream.index] ?: Long.MIN_VALUE
                                if (adjustedDts <= prevDts) {
                                    // Discontinuity: bump offset so this packet is strictly after the last one.
                                    val bump = prevDts - adjustedDts + 1
                                    val newOffset = offset + bump
                                    dtsOffsets[outStream.index] = newOffset
                                    packet.dts = dts + newOffset
                                    if (pts != AVPacket.NOPTS) packet.pts = pts + newOffset
                                    lastDts[outStream.index] = packet.dts
                                } else {
                                    packet.dts = adjustedDts
                                    if (pts != AVPacket.NOPTS) packet.pts = pts + offset
                                    lastDts[outStream.index] = adjustedDts
                                }
                            }

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
