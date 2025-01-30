/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.vlc.loader

import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryProviderPriority
import java.io.File

/**
 * A [DiscoveryDirectoryProvider] that looks for resources in a directory specified by the system property `compose.application.resources.dir`.
 *
 * This is usually useful when the app is packed as an application using the Compose Gradle plugin.
 */
public class ComposeResourcesDiscoveryDirectoryProvider : DiscoveryDirectoryProvider {
    override fun priority(): Int = DiscoveryProviderPriority.USER_DIR

    override fun directories(): Array<String> {
        val path = System.getProperty("compose.application.resources.dir") ?: return emptyArray()
        val libs = File(path).resolve("lib")
        if (!libs.exists()) return emptyArray()
        return arrayOf(libs.absolutePath)
    }

    override fun supported(): Boolean = true
}