/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class RemuxTest {
    @Test
    fun remux_sampleMedia() {
        val input = sampleMediaPath()
        if (input.isEmpty()) return
        val output = tempOutputPath("-remux.mp4")

        val result = MediaTranscoder().execute(MediaOperation.Remux(input, output))
        assertTrue(result.isSuccess, "Remux should succeed")

        // Verify output is a valid media file
        MediaInput().use { media ->
            media.open(output)
            media.findStreamInfo()
            assertTrue(media.streamCount > 0, "Remuxed file should have streams")
        }
    }

    @Test
    fun remux_via_FFmpegKit_execute() = runTest {
        val input = sampleMediaPath()
        if (input.isEmpty()) return@runTest
        val output = tempOutputPath("-remux-kit.mp4")

        // This should be parsed as Remux and executed via the thin wrapper
        val args = listOf("-i", input, "-c", "copy", output)
        val result = FFmpegKit().execute(args)
        assertTrue(result.isSuccess, "FFmpegKit remux should succeed")

        MediaInput().use { media ->
            media.open(output)
            media.findStreamInfo()
            assertTrue(media.streamCount > 0, "FFmpegKit remux output should have streams")
        }
    }
}
