/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.vlc.loader

import org.openani.mediamp.internal.Arch
import org.openani.mediamp.internal.Platform
import org.openani.mediamp.internal.currentPlatformDesktop
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryProviderPriority
import java.io.File

/**
 * A [DiscoveryDirectoryProvider] that looks for resources in the `appResources` directory from the current directory.
 */
public class TestDiscoveryDirectoryProvider : DiscoveryDirectoryProvider {
    override fun priority(): Int = DiscoveryProviderPriority.USER_DIR

    override fun directories(): Array<String> {
        val baseDir = baseDir ?: return emptyArray()

        val platform = currentPlatformDesktop()
        val os = when (platform) {
            is Platform.MacOS -> "macos"
            is Platform.Windows -> "windows"
            is Platform.Linux -> "linux"
        }

        val arch = when (platform.arch) {
            Arch.X86_64 -> "x64"
            Arch.AARCH64 -> "arm64"

            Arch.ARMV7A, Arch.ARMV8A ->
                throw UnsupportedOperationException("Unsupported architecture: ${platform.arch}")
        }

        val libs = baseDir.resolve("${os}-${arch}/lib")
        if (!libs.exists()) return emptyArray()
        return arrayOf(libs.absolutePath)
    }

    override fun supported(): Boolean = true

    internal companion object {
        var baseDir: File? = null
    }
}
