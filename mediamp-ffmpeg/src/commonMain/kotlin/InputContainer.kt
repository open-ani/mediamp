/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

/**
 * A container for reading multimedia data from an input file.
 *
 * This is a Kotlin wrapper around FFmpeg's `AVFormatContext`, providing
 * `AutoCloseable` lifecycle management and a stream-oriented API.
 */
public expect class InputContainer() : AutoCloseable {
    /**
     * Open an input file or URL.
     *
     * @param url The input file path or URL.
     * @param options A map of format-private options passed to `avformat_open_input`.
     *                Common keys include `allowed_extensions` and `protocol_whitelist`.
     * @param ignoreDts If true, sets `AVFMT_FLAG_IGNDTS` on the input context.
     *                  This ignores input DTS and derives it from PTS,
     *                  which is often required when remuxing HLS/MPEG-TS to MP4.
     */
    public fun open(url: String, options: Map<String, String> = emptyMap(), ignoreDts: Boolean = false)

    /**
     * Read stream information (codecs, durations, etc.).
     */
    public fun findStreamInfo(): Int

    /**
     * The list of streams in this container.
     */
    public val streams: List<Stream>

    /**
     * Read the next packet into [packet].
     *
     * @return 0 on success, negative AVERROR on failure or EOF.
     */
    public fun readPacket(packet: AVPacket): Int

    /**
     * Close the container and release all resources.
     */
    public override fun close()
}
