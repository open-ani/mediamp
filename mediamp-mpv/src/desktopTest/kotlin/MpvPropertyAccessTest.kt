/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MpvPropertyAccessTest {

    private fun prepareOrSkip(): Boolean {
        val dir = System.getProperty("mediamp.mpv.dev.native.dir")
            ?.let(::File)
            ?.takeIf {
                it.resolve("mediampv.dll").isFile || it.resolve("libmediampv.dylib").isFile ||
                    it.resolve("libmediampv.so").isFile
            }
        if (dir == null) {
            System.err.println("[MpvPropertyAccessTest] skipped: no native runtime")
            check(System.getProperty("mediamp.mpv.test.required") != "true") {
                "mpv native tests are required on this runner but would be skipped"
            }
            return false
        }
        MpvMediampPlayer.prepareLibraries(dir.absolutePath, extractRuntimeLibrary = false)
        return true
    }

    @Test
    fun `OrNull getters tell unavailable apart from zero`() {
        if (!prepareOrSkip()) return
        MPVHandle(Any()).use { handle ->
            handle.option("vo", "null")
            handle.option("ao", "null")
            handle.option("demuxer-max-bytes", "12345678")
            handle.initialize()

            // Nothing is loaded, so there is no position: the plain getter reads 0.0.
            assertEquals(0.0, handle.getPropertyDouble("time-pos"))
            assertNull(handle.getPropertyDoubleOrNull("time-pos"))
            assertNull(handle.getPropertyLongOrNull("width"))
            assertNull(handle.getPropertyBooleanOrNull("no-such-property"))

            assertEquals(12345678L, handle.getPropertyLongOrNull("demuxer-max-bytes"))
            assertNotNull(handle.getPropertyBooleanOrNull("pause"))
        }
    }

    @Test
    fun `MPVFormat carries mpv_format values, not declaration order`() {
        // mpv/client.h
        assertEquals(0, MPVFormat.MPV_FORMAT_NONE.nativeValue)
        assertEquals(1, MPVFormat.MPV_FORMAT_STRING.nativeValue)
        assertEquals(3, MPVFormat.MPV_FORMAT_FLAG.nativeValue)
        assertEquals(4, MPVFormat.MPV_FORMAT_INT64.nativeValue)
        assertEquals(5, MPVFormat.MPV_FORMAT_DOUBLE.nativeValue)
        assertEquals(9, MPVFormat.MPV_FORMAT_BYTE_ARRAY.nativeValue)
    }
}
