/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

/**
 * A bitstream filter that can be applied to a stream during remux.
 *
 * This wraps FFmpeg's `AVBSFContext` and is used for operations such as
 * converting AAC ADTS to AudioSpecificConfig (`aac_adtstoasc`).
 *
 * Example usage in a remux loop:
 * ```
 * bsf.sendPacket(packet)
 * while (bsf.receivePacket(filteredPacket)) {
 *     output.mux(filteredPacket, outStream)
 * }
 * ```
 */
public expect class BitstreamFilter(
    name: String,
    stream: Stream,
) : AutoCloseable {
    /**
     * Send a packet into the bitstream filter.
     *
     * After calling this, call [receivePacket] repeatedly to retrieve
     * filtered packets.
     */
    public fun sendPacket(packet: AVPacket)

    /**
     * Receive a filtered packet from the bitstream filter.
     *
     * @return `true` if a packet was written to [packet], `false` if
     *         the filter needs more input or has reached EOF.
     */
    public fun receivePacket(packet: AVPacket): Boolean

    public override fun close()
}
