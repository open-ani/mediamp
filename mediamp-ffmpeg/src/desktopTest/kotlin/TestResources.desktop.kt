/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

internal actual fun sampleMediaPath(): String =
    Thread.currentThread().contextClassLoader.getResource("sample.mp4")?.path
        ?: error("sample.mp4 not found in test resources")

internal actual fun tempOutputPath(suffix: String): String =
    System.getProperty("java.io.tmpdir").trimEnd('/', '\\')
        .let { "$it/temp-test$suffix" }
