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

class BatchExecutionTest {
    @Test
    fun sequentialRemux_doesNotPolluteGlobalState() = runTest {
        val input = sampleMediaPath()
        if (input.isEmpty()) return@runTest

        repeat(10) { index ->
            val output = tempOutputPath("-batch-$index.mp4")
            val result = MediaTranscoder().execute(MediaOperation.Remux(input, output))
            assertTrue(result.isSuccess, "Batch remux $index should succeed")

            // Verify output is valid
            MediaInput().use { media ->
                media.open(output)
                media.findStreamInfo()
                assertTrue(media.streamCount > 0, "Batch output $index should have streams")
            }
        }
    }
}
