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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HlsRemuxTest {
    @Test
    fun remuxWithMovflagsProducesFaststartMp4() {
        val input = sampleMediaPath()
        val output = tempOutputPath("-faststart.mp4")

        val result = MediaTranscoder().execute(
            MediaOperation.Remux(
                input = input,
                output = output,
                movflags = listOf("faststart"),
            )
        )
        assertTrue(result.isSuccess, "Remux with movflags should succeed")

        // Verify moov atom is before mdat
        assertTrue(isMoovBeforeMdat(output), "moov should be before mdat for faststart")
    }

    private fun isMoovBeforeMdat(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        val bytes = file.readBytes()

        fun findAtom(name: String): Int {
            var i = 0
            while (i < bytes.size - 8) {
                val size = (
                    ((bytes[i].toInt() and 0xFF) shl 24) or
                        ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                        ((bytes[i + 2].toInt() and 0xFF) shl 8) or
                        (bytes[i + 3].toInt() and 0xFF)
                    )
                val atom = bytes.copyOfRange(i + 4, i + 8).toString(Charsets.US_ASCII)
                if (atom == name) return i
                if (size <= 0 || i + size > bytes.size) break
                i += if (size == 1) {
                    // extended size
                    val extendedSize = (
                        ((bytes[i + 8].toLong() and 0xFF) shl 56) or
                            ((bytes[i + 9].toLong() and 0xFF) shl 48) or
                            ((bytes[i + 10].toLong() and 0xFF) shl 40) or
                            ((bytes[i + 11].toLong() and 0xFF) shl 32) or
                            ((bytes[i + 12].toLong() and 0xFF) shl 24) or
                            ((bytes[i + 13].toLong() and 0xFF) shl 16) or
                            ((bytes[i + 14].toLong() and 0xFF) shl 8) or
                            (bytes[i + 15].toLong() and 0xFF)
                        )
                    extendedSize.toInt()
                } else {
                    size
                }
            }
            return -1
        }

        // Look inside ftyp/moov/mdat structure, or skip ftyp
        var offset = 0
        while (offset < bytes.size - 8) {
            val size = (
                ((bytes[offset].toInt() and 0xFF) shl 24) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)
                )
            val atom = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            if (atom == "moov") {
                // Check if mdat comes after moov
                val mdatPos = findAtom("mdat")
                return mdatPos > offset
            }
            if (size <= 0 || offset + size > bytes.size) break
            offset += size
        }
        return false
    }
}
