/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import java.io.File
import org.openani.mediamp.nativeloader.NativeClasspathRuntime
import org.openani.mediamp.nativeloader.NativeRuntimeLoader

internal actual object LibraryLoader {
    private const val WRAPPER_NAME: String = "mediampv"
    private val CLASSPATH_RUNTIME: NativeClasspathRuntime =
        NativeClasspathRuntime(
            wrapperName = WRAPPER_NAME,
            manifestResourceName = "mpv-natives.txt",
            temporaryDirectoryPrefix = "mediamp-mpv",
            resourceClassLoader = MPVHandle::class.java.classLoader,
        )

    actual fun setRuntimeLibraryDirectory(path: String, extractRuntimeLibrary: Boolean) {
        require(path.isNotBlank()) { "mpv runtime directory must not be blank." }
        NativeRuntimeLoader.setRuntimeDirectory(WRAPPER_NAME, File(path), extractRuntimeLibrary)
    }

    actual fun useDefaultRuntimeLibraryDirectory() {
        NativeRuntimeLoader.useDefaultRuntimeDirectory(WRAPPER_NAME)
    }

    actual fun loadLibraries(context: Any?) {
        NativeRuntimeLoader.ensureConfiguredClasspathRuntimeLoaded(CLASSPATH_RUNTIME)
    }
}
