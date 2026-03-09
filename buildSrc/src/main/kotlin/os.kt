/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import java.util.Locale

// get current os
enum class Os {
    Windows,
    MacOS,
    Linux,
    Unknown
}

fun getOs(): Os {
    val os = System.getProperty("os.name").lowercase(Locale.getDefault())
    return when {
        os.contains("win") -> Os.Windows
        os.contains("mac") -> Os.MacOS
        os.contains("nux") -> Os.Linux
        else -> Os.Unknown
    }
}

enum class Arch {
    X86_64,
    AARCH64,
    UNKNOWN,
}

fun getArch(): Arch {
    val archName = System.getProperty("os.arch").lowercase(Locale.getDefault())
    return when (archName) {
        "x86_64", "amd64" -> Arch.X86_64
        "aarch64", "arm64" -> Arch.AARCH64
        else -> Arch.UNKNOWN
    }
}

fun getOsTriple(): String {
    return when (getOs()) {
        Os.Windows -> "windows-x64"
        Os.MacOS -> if (getArch() == Arch.AARCH64) "macos-aarch64" else "macos-x64"
        Os.Linux -> "linux-x64"
        Os.Unknown -> throw UnsupportedOperationException("Unknown OS")
    }
}
