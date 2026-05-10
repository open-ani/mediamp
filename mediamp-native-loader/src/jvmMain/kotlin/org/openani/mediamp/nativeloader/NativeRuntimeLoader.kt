/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.nativeloader

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Classpath-packaged native runtime descriptor.
 */
public class NativeClasspathRuntime(
    public val libraryName: String,
    public val manifestResourceName: String,
    public val resourceClassLoader: ClassLoader,
)

/**
 * Shared runtime loader for JNI/native wrapper libraries.
 */
public object NativeRuntimeLoader {
    private val loadLock: Any = Any()
    private val loadedRuntimeDirs: MutableMap<String, String> = mutableMapOf()

    /**
     * Configure and immediately load a native runtime.
     *
     * @param runtime classpath runtime descriptor.
     * @param path target runtime directory.
     * @param doExtract extract all files listed in [NativeClasspathRuntime.manifestResourceName] into [path].
     * @param validate verify that the main wrapper library exists in [path] after optional extraction.
     */
    public fun setRuntimeDirectory(
        runtime: NativeClasspathRuntime,
        path: File,
        doExtract: Boolean,
        validate: Boolean,
    ) {
        val runtimeDir = path.canonicalFile
        synchronized(loadLock) {
            val loadedPath = loadedRuntimeDirs[runtime.libraryName]
            check(loadedPath == null || loadedPath == runtimeDir.absolutePath) {
                "Native runtime '${runtime.libraryName}' is already loaded from $loadedPath and cannot be reconfigured."
            }
            if (loadedPath != null) {
                return
            }

            val wrapperFile = runtimeDir.resolve(nativeLibraryFileName(runtime.libraryName))

            if (validate && wrapperFile.isFile) {
                loadRuntimeWrapperLibrary(runtimeDir, runtime.libraryName)
                loadedRuntimeDirs[runtime.libraryName] = runtimeDir.absolutePath
                return
            }

            if (doExtract) {
                extractClasspathRuntime(runtime, runtimeDir)
                if (validate) {
                    require(wrapperFile.isFile) {
                        "Native runtime wrapper not found at ${wrapperFile.absolutePath}. " +
                                "Ensure the runtime artifact was packaged correctly."
                    }
                }
            }

            loadRuntimeWrapperLibrary(runtimeDir, runtime.libraryName)
            loadedRuntimeDirs[runtime.libraryName] = runtimeDir.absolutePath
        }
    }

    private fun extractClasspathRuntime(runtime: NativeClasspathRuntime, targetDir: File) {
        if (targetDir.exists()) {
            require(targetDir.isDirectory) {
                "Configured native runtime directory must be a directory: ${targetDir.absolutePath}"
            }
        } else {
            targetDir.mkdirs()
        }

        val manifest = runtime.resourceClassLoader
            .getResourceAsStream(runtime.manifestResourceName)
            ?.bufferedReader()
            ?.use { it.readLines() }
            ?: error(
                "${runtime.manifestResourceName} not found on the classpath. " +
                        "Make sure the matching native runtime artifact is present at runtime.",
            )

        for (fileName in manifest) {
            if (fileName.isBlank()) continue

            val targetFile = targetDir.resolve(fileName)
            targetFile.parentFile?.mkdirs()

            runtime.resourceClassLoader.getResourceAsStream(fileName)?.use { input ->
                Files.copy(input, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } ?: error(
                "Native runtime file '$fileName' listed in ${runtime.manifestResourceName} was not found on the classpath.",
            )

            if (!isWindowsRuntime()) {
                targetFile.setReadable(true, false)
                targetFile.setExecutable(true, false)
            }
        }
    }

}

internal fun nativeLibraryFileName(libraryName: String): String {
    val osName: String = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    return when {
        osName.contains("win") -> "$libraryName.dll"
        osName.contains("mac") -> "lib$libraryName.dylib"
        else -> "lib$libraryName.so"
    }
}

internal fun isWindowsRuntime(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win")

internal expect fun loadRuntimeWrapperLibrary(runtimeDir: File, libraryName: String)
