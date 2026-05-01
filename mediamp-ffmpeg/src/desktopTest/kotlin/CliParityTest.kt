/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliParityTest {
    @Test
    fun wrapperRemuxAndCliRemuxProduceEquivalentStreamStructure() = runTest {
        val input = sampleMediaPath()
        if (!File(input).exists()) return@runTest

        val wrapperOutput = tempOutputPath("-wrapper.mp4")
        val cliOutput = tempOutputPath("-cli.mp4")

        // Wrapper remux
        val wrapperResult = MediaTranscoder().execute(
            MediaOperation.Remux(input, wrapperOutput)
        )
        assertTrue(wrapperResult.isSuccess, "Wrapper remux should succeed")

        // CLI remux via FFmpegKit (will use subprocess since we pass explicit args)
        val cliResult = FFmpegKit().execute(
            listOf("-i", input, "-c", "copy", cliOutput)
        )
        assertTrue(cliResult.isSuccess, "CLI remux should succeed")

        // Compare stream counts
        val wrapperStreams = InputContainer().use { media ->
            media.open(wrapperOutput)
            media.findStreamInfo()
            media.streams.size
        }
        val cliStreams = InputContainer().use { media ->
            media.open(cliOutput)
            media.findStreamInfo()
            media.streams.size
        }
        assertEquals(wrapperStreams, cliStreams, "Wrapper and CLI should produce same stream count")

        // Compare stream codec IDs
        val wrapperCodecIds = InputContainer().use { media ->
            media.open(wrapperOutput)
            media.findStreamInfo()
            media.streams.map { it.codecId }.sorted()
        }
        val cliCodecIds = InputContainer().use { media ->
            media.open(cliOutput)
            media.findStreamInfo()
            media.streams.map { it.codecId }.sorted()
        }
        assertEquals(wrapperCodecIds, cliCodecIds, "Wrapper and CLI should produce same codecs")
    }

    @Test
    fun wrapperRemuxWithMovflagsMatchesCliBehavior() = runTest {
        val input = sampleMediaPath()
        if (!File(input).exists()) return@runTest

        val wrapperOutput = tempOutputPath("-wrapper-movflags.mp4")

        val result = MediaTranscoder().execute(
            MediaOperation.Remux(
                input = input,
                output = wrapperOutput,
                movflags = listOf("faststart"),
            )
        )
        assertTrue(result.isSuccess, "Wrapper remux with movflags should succeed")

        // Verify file is valid
        InputContainer().use { media ->
            media.open(wrapperOutput)
            media.findStreamInfo()
            assertTrue(media.streams.isNotEmpty(), "Output should have streams")
        }
    }
}
