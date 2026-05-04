/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

public object ArgsParser {
    public fun parse(args: List<String>): MediaOperation? {
        val iter = args.listIterator()
        var input: String? = null
        var output: String? = null
        var copyCodec = false
        var videoCodec: String? = null
        var audioCodec: String? = null
        val bitstreamFilters = mutableMapOf<Int, String>()
        val movflags = mutableListOf<String>()
        var allowedExtensions: String? = null
        var protocolWhitelist: String? = null

        while (iter.hasNext()) {
            when (val arg = iter.next()) {
                "-i" -> {
                    if (iter.hasNext()) input = iter.next()
                }

                "-c", "-codec", "-acodec", "-vcodec" -> {
                    if (iter.hasNext()) {
                        val value = iter.next()
                        when (arg) {
                            "-c", "-codec" -> {
                                if (value == "copy") copyCodec = true
                            }

                            "-vcodec" -> videoCodec = value
                            "-acodec" -> audioCodec = value
                        }
                    }
                }

                "-bsf:a" -> {
                    if (iter.hasNext()) {
                        bitstreamFilters[1] = iter.next() // default audio stream index
                    }
                }

                "-bsf:v" -> {
                    if (iter.hasNext()) {
                        bitstreamFilters[0] = iter.next() // default video stream index
                    }
                }

                "-bsf" -> {
                    if (iter.hasNext()) {
                        val value = iter.next()
                        // Apply to all streams (simplified)
                        bitstreamFilters[-1] = value
                    }
                }

                "-movflags" -> {
                    if (iter.hasNext()) {
                        val value = iter.next()
                        movflags.addAll(value.removePrefix("+").split("+"))
                    }
                }

                "-allowed_extensions" -> {
                    if (iter.hasNext()) allowedExtensions = iter.next()
                }

                "-protocol_whitelist" -> {
                    if (iter.hasNext()) protocolWhitelist = iter.next()
                }

                else -> {
                    if (arg.startsWith("-")) {
                        // Unknown option; we can't safely determine whether it consumes
                        // an operand. Fall back to real ffmpeg execution so semantics
                        // are preserved (e.g. -map 0 should not be misinterpreted).
                        return null
                    }
                    if (input != null && output == null) {
                        output = arg
                    }
                }
            }
        }

        val inFile = input ?: return null
        val outFile = output ?: return MediaOperation.Probe(inFile)

        return if (copyCodec && videoCodec == null && audioCodec == null) {
            MediaOperation.Remux(
                input = inFile,
                output = outFile,
                bitstreamFilters = bitstreamFilters,
                movflags = movflags,
                allowedExtensions = allowedExtensions,
                protocolWhitelist = protocolWhitelist,
            )
        } else {
            MediaOperation.Transcode(inFile, outFile, videoCodec, audioCodec)
        }
    }
}
