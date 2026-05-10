/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import java.io.File
import java.nio.file.Files
import org.openani.mediamp.nativeloader.NativeClasspathRuntime
import org.openani.mediamp.nativeloader.NativeRuntimeLoader

internal actual object LibraryLoader {
    private const val WRAPPER_NAME: String = "mediampv"
    private val CLASSPATH_RUNTIME: NativeClasspathRuntime =
        NativeClasspathRuntime(
            libraryName = WRAPPER_NAME,
            manifestResourceName = "mpv-natives.txt",
            resourceClassLoader = MPVHandle::class.java.classLoader ?: ClassLoader.getSystemClassLoader(),
        )

    @Volatile
    private var defaultRuntimeDirectory: File? = null

    actual fun setRuntimeLibraryDirectory(path: String, extractRuntimeLibrary: Boolean) {
        require(path.isNotBlank()) { "mpv runtime directory must not be blank." }
        NativeRuntimeLoader.setRuntimeDirectory(
            runtime = CLASSPATH_RUNTIME,
            path = File(path),
            doExtract = extractRuntimeLibrary,
            validate = true,
        )
    }

    actual fun useDefaultRuntimeLibraryDirectory() {
        NativeRuntimeLoader.setRuntimeDirectory(
            runtime = CLASSPATH_RUNTIME,
            path = defaultRuntimeDirectory(),
            doExtract = true,
            validate = true,
        )
    }

    actual fun loadLibraries(context: Any?) {
        // Desktop runtimes are loaded immediately by setRuntimeLibraryDirectory/useDefaultRuntimeLibraryDirectory.
    }

    private fun defaultRuntimeDirectory(): File =
        defaultRuntimeDirectory ?: synchronized(this) {
            defaultRuntimeDirectory ?: Files.createTempDirectory("mediamp-mpv").toFile().canonicalFile
                .also { runtimeDir ->
                    runtimeDir.deleteOnExit()
                    defaultRuntimeDirectory = runtimeDir
                }
        }
}
