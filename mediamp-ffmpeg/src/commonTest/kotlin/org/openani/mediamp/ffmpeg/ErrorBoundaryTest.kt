/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ErrorBoundaryTest {
    @Test
    fun openNonExistentFileThrows() {
        assertFailsWith<FFmpegException> {
            InputContainer().use { it.open("/does/not/exist.mp4") }
        }
    }

    @Test
    fun openInvalidPathThrows() {
        assertFailsWith<FFmpegException> {
            InputContainer().use { it.open("") }
        }
    }

    @Test
    fun outputContainerCanBeClosedCleanlyWithoutWriteHeader() {
        // Ensure OutputContainer can be closed cleanly even if writeHeader never called
        OutputContainer().use { output ->
            output.open(tempOutputPath("-no-header.mp4"))
            // writeHeader intentionally not called
        }
    }

    @Test
    fun decoderContext_openInvalidCodecIdThrows() {
        assertFailsWith<FFmpegException> {
            DecoderContext().use { it.open(0) }
        }
    }

    @Test
    fun readPacketBeforeFindStreamInfoSucceedsOrThrows() {
        val path = sampleMediaPath()
        if (path.isEmpty()) return
        InputContainer().use { input ->
            input.open(path)
            // findStreamInfo intentionally not called
            AVPacket().use { packet ->
                val ret = input.readPacket(packet)
                // Should either succeed or return a negative error code
                assertTrue(ret != 0 || packet.streamIndex() >= 0, "readPacket should return data or error")
            }
        }
    }
}
