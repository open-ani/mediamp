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

                else -> {
                    if (!arg.startsWith("-") && input != null && output == null) {
                        output = arg
                    }
                }
            }
        }

        val inFile = input ?: return null
        val outFile = output ?: return MediaOperation.Probe(inFile)

        return if (copyCodec && videoCodec == null && audioCodec == null) {
            MediaOperation.Remux(inFile, outFile)
        } else {
            MediaOperation.Transcode(inFile, outFile, videoCodec, audioCodec)
        }
    }
}
