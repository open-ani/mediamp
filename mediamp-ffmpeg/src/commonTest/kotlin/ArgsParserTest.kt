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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArgsParserTest {
    @Test
    fun parseRemuxWithBsfAndMovflags() {
        val args = listOf(
            "-i", "input.ts",
            "-c", "copy",
            "-bsf:a", "aac_adtstoasc",
            "-movflags", "+faststart",
            "output.mp4",
        )
        val op = ArgsParser.parse(args)
        assertNotNull(op)
        assertTrue(op is MediaOperation.Remux)
        assertEquals("input.ts", op.input)
        assertEquals("output.mp4", op.output)
        assertEquals(mapOf(1 to "aac_adtstoasc"), op.bitstreamFilters)
        assertEquals(listOf("faststart"), op.movflags)
    }

    @Test
    fun parseRemuxWithMovflagsMultiple() {
        val args = listOf(
            "-i", "input.ts",
            "-c", "copy",
            "-movflags", "+faststart+frag_keyframe",
            "output.mp4",
        )
        val op = ArgsParser.parse(args)
        assertNotNull(op)
        assertTrue(op is MediaOperation.Remux)
        assertEquals(listOf("faststart", "frag_keyframe"), op.movflags)
    }

    @Test
    fun parseProbe() {
        val args = listOf("-i", "input.mp4")
        val op = ArgsParser.parse(args)
        assertNotNull(op)
        assertTrue(op is MediaOperation.Probe)
        assertEquals("input.mp4", op.input)
    }

    @Test
    fun parseTranscode() {
        val args = listOf("-i", "input.mp4", "-vcodec", "libx264", "output.mp4")
        val op = ArgsParser.parse(args)
        assertNotNull(op)
        assertTrue(op is MediaOperation.Transcode)
    }

    @Test
    fun parseUnknownOptionReturnsNull() {
        // Unknown options should fall back to real ffmpeg execution,
        // so their operands are not misinterpreted as output paths.
        val args = listOf(
            "-i", "input.mp4",
            "-map", "0", // -map is unknown; "0" must not become the output
            "-c", "copy",
            "output.mp4",
        )
        val op = ArgsParser.parse(args)
        assertEquals(null, op)
    }
}
