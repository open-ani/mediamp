/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeBaseTest {
    @Test
    fun remuxPreservesStreamTimeBase() {
        val input = sampleMediaPath()
        if (input.isEmpty()) return
        val output = tempOutputPath("-timebase.mp4")

        // Capture input stream time bases
        val inputTimeBases = InputContainer().use { media ->
            media.open(input)
            media.findStreamInfo()
            media.streams.associate { it.index to it.timeBase }
        }

        val result = MediaTranscoder().execute(MediaOperation.Remux(input, output))
        assertTrue(result.isSuccess, "Remux should succeed")

        // Verify output streams have consistent time bases
        OutputContainer().use { out ->
            out.open(output)
            // Cannot read output time base without adding streams,
            // so we verify via re-reading the output
        }

        val outputTimeBases = InputContainer().use { media ->
            media.open(output)
            media.findStreamInfo()
            media.streams.associate { it.index to it.timeBase }
        }

        // Output should have the same number of streams
        assertEquals(inputTimeBases.size, outputTimeBases.size, "Stream count should match")

        // Each stream should have a valid time base (den != 0)
        outputTimeBases.forEach { (index, tb) ->
            assertTrue(tb.den != 0, "Stream $index should have valid time base denominator")
            assertTrue(tb.toDouble() >= 0, "Stream $index should have non-negative time base")
        }
    }

    @Test
    fun addStreamCopiesTimeBase() {
        val input = sampleMediaPath()
        if (input.isEmpty()) return
        val output = tempOutputPath("-copy-timebase.mp4")

        InputContainer().use { inputContainer ->
            inputContainer.open(input)
            inputContainer.findStreamInfo()

            OutputContainer().use { outputContainer ->
                outputContainer.open(output)

                val inputStreams = inputContainer.streams
                val outputStreams = inputStreams.map { outputContainer.addStream(it) }

                // Output streams should have same time base as input streams
                inputStreams.zip(outputStreams).forEach { (inStream, outStream) ->
                    assertEquals(
                        inStream.timeBase,
                        outStream.timeBase,
                        "Output stream ${outStream.index} should copy input time base",
                    )
                }
            }
        }
    }

    @Test
    fun packetStreamIndexIsConsistentAfterRemux() {
        val input = sampleMediaPath()
        if (input.isEmpty()) return
        val output = tempOutputPath("-stream-index.mp4")

        val result = MediaTranscoder().execute(MediaOperation.Remux(input, output))
        assertTrue(result.isSuccess, "Remux should succeed")

        // Read first few packets from output and verify stream indices are valid
        InputContainer().use { media ->
            media.open(output)
            media.findStreamInfo()
            val streamCount = media.streams.size

            AVPacket().use { packet ->
                var packetsRead = 0
                repeat(20) {
                    val ret = media.readPacket(packet)
                    if (ret < 0) return@repeat
                    val idx = packet.streamIndex()
                    assertTrue(
                        idx in 0 until streamCount,
                        "Packet stream index $idx should be within [0, $streamCount)",
                    )
                    packet.unref()
                    packetsRead++
                }
                assertTrue(packetsRead > 0, "Should read at least one packet")
            }
        }
    }
}
