/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ResourceLeakTest {
    @Test
    fun oneThousandSequentialOpenCloseCycles() {
        val path = sampleMediaPath()
        if (!File(path).exists()) return

        val initialFdCount = countOpenFileDescriptors()

        repeat(1000) {
            InputContainer().use { input ->
                input.open(path)
                input.findStreamInfo()
            }
        }

        // Force GC to trigger finalizers if any
        System.gc()
        Thread.sleep(100)

        val finalFdCount = countOpenFileDescriptors()
        // Allow some tolerance for JVM/runtime overhead
        assertTrue(
            finalFdCount <= initialFdCount + 10,
            "File descriptor leak suspected: initial=$initialFdCount final=$finalFdCount",
        )
    }

    @Test
    fun repeatedRemuxDoesNotLeak() {
        val path = sampleMediaPath()
        if (!File(path).exists()) return

        val initialFdCount = countOpenFileDescriptors()

        repeat(50) { index ->
            val output = tempOutputPath("-leak-$index.mp4")
            val result = MediaTranscoder().execute(MediaOperation.Remux(path, output))
            assertTrue(result.isSuccess, "Remux $index should succeed")
        }

        System.gc()
        Thread.sleep(100)

        val finalFdCount = countOpenFileDescriptors()
        assertTrue(
            finalFdCount <= initialFdCount + 20,
            "File descriptor leak after remux: initial=$initialFdCount final=$finalFdCount",
        )
    }

    private fun countOpenFileDescriptors(): Int {
        return try {
            val pid = ProcessHandle.current().pid()
            when {
                System.getProperty("os.name").lowercase().contains("linux") -> {
                    File("/proc/$pid/fd").list()?.size ?: 0
                }
                System.getProperty("os.name").lowercase().contains("mac") -> {
                    val process = ProcessBuilder("lsof", "-p", pid.toString())
                        .redirectErrorStream(true)
                        .start()
                    process.inputStream.bufferedReader().use { reader ->
                        reader.lineSequence().count()
                    }
                }
                else -> 0
            }
        } catch (_: Throwable) {
            0
        }
    }
}
