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
 * Classpath-packaged native runtime descriptor for desktop extraction/loading.
 */
public class NativeClasspathRuntime(
    public val wrapperName: String,
    public val manifestResourceName: String,
    public val temporaryDirectoryPrefix: String,
    public val resourceClassLoader: ClassLoader,
)

/**
 * Shared runtime loader for JNI/native wrapper libraries extracted to a runtime directory.
 */
public object NativeRuntimeLoader {
    private val loadLock: Any = Any()
    private val loadedRuntimeKeys: MutableSet<String> = mutableSetOf()
    private val configuredRuntimeDirs: MutableMap<String, RuntimeDirectoryConfiguration> = mutableMapOf()

    public fun setRuntimeDirectory(
        wrapperName: String,
        runtimeDir: File,
        extractRuntimeLibrary: Boolean,
    ) {
        val normalizedPath = runtimeDir.canonicalFile.absolutePath
        synchronized(loadLock) {
            assertNotLoadedFromAnotherPath(wrapperName, normalizedPath)
            when (val existing = configuredRuntimeDirs[wrapperName]) {
                null -> {
                    configuredRuntimeDirs[wrapperName] = RuntimeDirectoryConfiguration.Custom(normalizedPath, extractRuntimeLibrary)
                }

                is RuntimeDirectoryConfiguration.Custom -> {
                    check(existing.path == normalizedPath) {
                        "Native runtime '$wrapperName' is already configured to use ${existing.path}."
                    }
                }

                is RuntimeDirectoryConfiguration.DefaultTemporary -> {
                    error(
                        "Native runtime '$wrapperName' is already configured via useDefaultRuntimeLibraryDirectory().",
                    )
                }
            }
        }
    }

    public fun useDefaultRuntimeDirectory(wrapperName: String) {
        synchronized(loadLock) {
            when (configuredRuntimeDirs[wrapperName]) {
                null -> {
                    configuredRuntimeDirs[wrapperName] = RuntimeDirectoryConfiguration.DefaultTemporary()
                }

                is RuntimeDirectoryConfiguration.DefaultTemporary -> Unit

                is RuntimeDirectoryConfiguration.Custom -> {
                    error(
                        "Native runtime '$wrapperName' is already configured via setRuntimeLibraryDirectory(...).",
                    )
                }
            }
        }
    }

    public fun ensureConfiguredClasspathRuntimeLoaded(runtime: NativeClasspathRuntime) {
        val runtimeDir = ensureConfiguredClasspathRuntimeDirectory(runtime)
        ensureRuntimeLoaded(runtimeDir, runtime.wrapperName)
    }

    public fun ensureRuntimeLoaded(
        runtimeDir: File,
        wrapperName: String,
    ) {
        val canonicalDir = runtimeDir.canonicalFile
        synchronized(loadLock) {
            val key = "$wrapperName|${canonicalDir.absolutePath}"
            if (key !in loadedRuntimeKeys) {
                loadRuntimeWrapperLibrary(canonicalDir, wrapperName)
                loadedRuntimeKeys += key
            }
        }
    }

    private fun ensureConfiguredClasspathRuntimeDirectory(runtime: NativeClasspathRuntime): File =
        synchronized(loadLock) {
            var extractLibrary = false
            val runtimeDir = when (val configuration = configuredRuntimeDirs[runtime.wrapperName]) {
                null -> {
                    error(
                        "Native runtime '${runtime.wrapperName}' is not configured. " +
                            "Call setRuntimeLibraryDirectory(...) or useDefaultRuntimeLibraryDirectory() before use.",
                    )
                }

                is RuntimeDirectoryConfiguration.Custom -> {
                    extractLibrary = configuration.doExtractRuntimeLibraries
                    File(configuration.path)
                }

                is RuntimeDirectoryConfiguration.DefaultTemporary -> {
                    extractLibrary = true
                    val resolvedPath = configuration.path ?: createTemporaryRuntimeDirectory(runtime)
                    configuredRuntimeDirs[runtime.wrapperName] =
                        RuntimeDirectoryConfiguration.DefaultTemporary(resolvedPath)
                    File(resolvedPath)
                }
            }.canonicalFile

            if (runtimeDir.resolve(nativeLibraryFileName(runtime.wrapperName)).isFile) {
                return@synchronized runtimeDir
            }

            if (extractLibrary) {
                extractClasspathRuntime(runtime, runtimeDir)
            }

            runtimeDir
        }

    private fun createTemporaryRuntimeDirectory(runtime: NativeClasspathRuntime): String {
        val runtimeDir = Files.createTempDirectory(runtime.temporaryDirectoryPrefix).toFile().canonicalFile
        runtimeDir.deleteOnExit()
        return runtimeDir.absolutePath
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

    private fun assertNotLoadedFromAnotherPath(wrapperName: String, normalizedPath: String) {
        val loadedPath = loadedRuntimeKeys
            .firstOrNull { it.startsWith("$wrapperName|") }
            ?.substringAfter('|')
        check(loadedPath == null || loadedPath == normalizedPath) {
            "Native runtime '$wrapperName' is already loaded from $loadedPath and cannot be reconfigured."
        }
    }
}

private sealed interface RuntimeDirectoryConfiguration {
    data class Custom(
        val path: String,
        val doExtractRuntimeLibraries: Boolean
    ) : RuntimeDirectoryConfiguration

    data class DefaultTemporary(
        val path: String? = null,
    ) : RuntimeDirectoryConfiguration
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

internal expect fun loadRuntimeWrapperLibrary(runtimeDir: File, wrapperName: String)
