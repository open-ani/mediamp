/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import java.io.File

internal actual fun sampleMediaPath(): String {
    val url = Thread.currentThread().contextClassLoader.getResource("sample.mp4")
        ?: error("sample.mp4 not found in test resources")
    return File(url.toURI()).absolutePath
}

internal actual fun tempOutputPath(suffix: String): String =
    File(System.getProperty("java.io.tmpdir"), "temp-test$suffix").absolutePath
