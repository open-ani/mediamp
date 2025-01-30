/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:Suppress("MemberVisibilityCanBePrivate")

package org.openani.mediamp.vlc.loader

import org.openani.mediamp.internal.Arch
import org.openani.mediamp.internal.Platform
import org.openani.mediamp.internal.currentPlatformDesktop
import java.io.File

public enum class VlcPlatformIdentifier(
    public val value: String
) {
    MACOS_X64("macos-x64"),
    MACOS_ARM64("macos-arm64"),
    WINDOWS_X64("windows-x64"),
    WINDOWS_ARM64("windows-arm64"),
    LINUX_X64("linux-x64"),
    LINUX_ARM64("linux-arm64");

    override fun toString(): String = value
}

public object MediampVlcLoader {
    /**
     * Enables test discovery for the VLC libraries.
     *
     * This function must be called before loading calling any Mediamp or VLC functions.
     *
     * Test discovery will look for the VLC libraries from `$baseDir/$platformIdentifier/lib`,
     * where `$baseDir` is [baseDir] and `$platformIdentifier` is [getCurrentPlatformIdentifier].
     * This is useful when debugging your app by running the main function directly.
     *
     * @param baseDir The base directory to look for the VLC libraries. Defaults to `appResources` in the current directory.
     */
    public fun enableTestDiscovery(
        baseDir: File = File(System.getProperty("user.dir"), "appResources")
    ) {
        TestDiscoveryDirectoryProvider.baseDir = baseDir
    }

    /**
     * Returns the unique identifier for the current platform.
     *
     * The returned values must be in the format `os-arch`, where `os` is the operating system and `arch` is the architecture.
     */
    public fun getCurrentPlatformIdentifier(): VlcPlatformIdentifier {
        val platform = currentPlatformDesktop()
        val arch = when (platform.arch) {
            Arch.X86_64 -> MyArch.X64
            Arch.AARCH64 -> MyArch.ARM64

            Arch.ARMV7A, Arch.ARMV8A ->
                throw UnsupportedOperationException("Unsupported architecture: ${platform.arch}")
        }

        return when (platform) {
            is Platform.MacOS -> when (arch) {
                MyArch.X64 -> VlcPlatformIdentifier.MACOS_X64
                MyArch.ARM64 -> VlcPlatformIdentifier.MACOS_ARM64
            }

            is Platform.Windows -> when (arch) {
                MyArch.X64 -> VlcPlatformIdentifier.WINDOWS_X64
                MyArch.ARM64 -> VlcPlatformIdentifier.WINDOWS_ARM64
            }

            is Platform.Linux -> when (arch) {
                MyArch.X64 -> VlcPlatformIdentifier.LINUX_X64
                MyArch.ARM64 -> VlcPlatformIdentifier.LINUX_ARM64
            }
        }
    }

    private enum class MyArch {
        X64, ARM64
    }
}

