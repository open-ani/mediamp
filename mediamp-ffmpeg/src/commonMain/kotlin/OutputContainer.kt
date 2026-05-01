/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

/**
 * A container for writing multimedia data to an output file.
 *
 * This is a Kotlin wrapper around FFmpeg's `AVFormatContext`, providing
 * `AutoCloseable` lifecycle management and automatic timestamp rescaling.
 */
public expect class OutputContainer() : AutoCloseable {
    /**
     * Create and open an output container.
     *
     * @param filename Output file path.
     * @param formatName Optional output format name (e.g. "mp4"). If null,
     *                   FFmpeg guesses from the filename extension.
     */
    public fun open(filename: String, formatName: String? = null)

    /**
     * Add a new stream copied from an input [stream].
     *
     * @return The newly created output stream.
     */
    public fun addStream(stream: Stream): Stream

    /**
     * Write the file header.
     *
     * Must be called before any [mux] operations.
     *
     * @param options Optional muxer options (e.g. `MuxerOptions().apply { movflags("faststart") }`).
     */
    public fun writeHeader(options: MuxerOptions? = null)

    /**
     * Mux a packet into the output stream.
     *
     * Timestamps are automatically rescaled from the input stream's time base
     * to the output stream's time base.
     */
    public fun mux(packet: AVPacket, stream: Stream)

    /**
     * Write the trailer and close the file.
     */
    public override fun close()
}
