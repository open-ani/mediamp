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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class WrapperSmokeTest {
    @Test
    fun checkError_throwsOnNegative() {
        val ex = assertFailsWith<FFmpegException> { (-1).checkError() }
        assertEquals(-1, ex.code)
    }

    @Test
    fun checkError_returnsSelfOnNonNegative() {
        assertEquals(0, 0.checkError())
        assertEquals(5, 5.checkError())
    }

    @Test
    fun avPacket_allocAndClose() {
        AVPacket().use { }
    }

    @Test
    fun avFrame_allocAndClose() {
        AVFrame().use { }
    }

    @Test
    fun mediaInput_openAndFindStreamInfo() {
        val path = sampleMediaPath()
        if (path.isEmpty()) {
            // Android stub — skip file I/O test
            return
        }
        MediaInput().use { input ->
            input.open(path)
            input.findStreamInfo()
            assertTrue(input.streamCount > 0, "Expected at least one stream")
        }
    }

    @Test
    fun mediaInput_readPacket() {
        val path = sampleMediaPath()
        if (path.isEmpty()) return
        MediaInput().use { input ->
            input.open(path)
            input.findStreamInfo()
            AVPacket().use { packet ->
                var readCount = 0
                repeat(10) {
                    val ret = input.readPacket(packet)
                    if (ret < 0) return@repeat
                    packet.unref()
                    readCount++
                }
                assertTrue(readCount > 0, "Expected to read at least one packet")
            }
        }
    }

    @Test
    fun decoderContext_lifecycle() {
        val path = sampleMediaPath()
        if (path.isEmpty()) return
        MediaInput().use { input ->
            input.open(path)
            input.findStreamInfo()
            val streamCount = input.streamCount
            var opened = false
            for (i in 0 until streamCount) {
                val codecId = input.codecId(i)
                if (codecId == 0) continue
                DecoderContext().use { decoder ->
                    decoder.open(codecId)
                    opened = true
                }
                break
            }
            assertTrue(opened, "Expected to open at least one decoder")
        }
    }

    @Test
    fun decoderContext_decodeFirstFrame() {
        val path = sampleMediaPath()
        if (path.isEmpty()) return
        MediaInput().use { input ->
            input.open(path)
            input.findStreamInfo()
            val streamCount = input.streamCount
            var decoded = false
            for (i in 0 until streamCount) {
                val codecId = input.codecId(i)
                if (codecId == 0) continue
                DecoderContext().use { decoder ->
                    decoder.open(codecId)
                    AVPacket().use { packet ->
                        AVFrame().use { frame ->
                            var sent = false
                            repeat(100) {
                                val ret = input.readPacket(packet)
                                if (ret < 0) return@repeat
                                if (sent) {
                                    packet.unref()
                                    return@repeat
                                }
                                decoder.sendPacket(packet)
                                packet.unref()
                                sent = true
                            }
                            if (sent) {
                                decoder.sendPacket(null)
                                val ret = decoder.receiveFrame(frame)
                                decoded = ret >= 0 || ret == AVERROR_EOF
                            }
                        }
                    }
                }
                break
            }
            assertTrue(decoded, "Expected to decode at least one frame")
        }
    }
}
