/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getcwd
import platform.posix.access
import platform.Foundation.NSBundle

@OptIn(ExperimentalForeignApi::class)
internal actual fun sampleMediaPath(): String {
    val candidates = mutableListOf<String>()

    // Try current working directory + resources
    memScoped {
        val buf = allocArray<ByteVar>(4096)
        val cwd = getcwd(buf, 4096u)?.toKString()
        if (cwd != null) {
            candidates.add("$cwd/resources/sample.mp4")
            candidates.add("$cwd/sample.mp4")
        }
    }

    // Try NSBundle resource path
    NSBundle.mainBundle.resourcePath?.let {
        candidates.add("$it/sample.mp4")
        candidates.add("$it/resources/sample.mp4")
    }

    // Try executable path
    NSBundle.mainBundle.executablePath?.let {
        candidates.add("${it.substringBeforeLast("/")}/resources/sample.mp4")
    }

    // Fallback relative paths
    candidates.add("resources/sample.mp4")
    candidates.add("../resources/sample.mp4")
    candidates.add("../../resources/sample.mp4")

    for (path in candidates) {
        if (access(path, 0) == 0) {
            return path
        }
    }

    // If nothing found, return first candidate so the test fails with a clear path
    return candidates.firstOrNull() ?: "resources/sample.mp4"
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun tempOutputPath(suffix: String): String {
    memScoped {
        val buf = allocArray<ByteVar>(4096)
        val cwd = getcwd(buf, 4096u)?.toKString() ?: "."
        return "$cwd/temp-test$suffix"
    }
}
