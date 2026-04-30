/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg.ffi

import kotlin.test.Test
import kotlin.test.assertTrue

class FfiSmokeTestTest {
    @Test
    fun avVersionInfo_returnsNonEmptyString() {
        val version = avVersionInfo()
        assertTrue(version.isNotEmpty(), "Expected non-empty version string, got: $version")
    }
}
