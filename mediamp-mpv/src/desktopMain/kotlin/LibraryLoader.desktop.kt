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
import java.util.Locale
import org.openani.mediamp.nativeloader.NativeClasspathRuntime
import org.openani.mediamp.nativeloader.NativeRuntimeLoader

internal actual object LibraryLoader {
    private const val WRAPPER_NAME: String = "mediampv"

    private val classLoader: ClassLoader =
        MPVHandle::class.java.classLoader ?: ClassLoader.getSystemClassLoader()

    private val manifestResourceName: String =
        selectMpvManifestResource(currentPlatformSuffix()) { classLoader.getResource(it) != null }

    private val CLASSPATH_RUNTIME: NativeClasspathRuntime =
        NativeClasspathRuntime(
            libraryName = WRAPPER_NAME,
            manifestResourceName = manifestResourceName,
            resourceClassLoader = classLoader,
        )

    @Volatile
    private var defaultRuntimeDirectory: File? = null

    @Volatile
    private var runtimeConfigured = false

    actual fun setRuntimeLibraryDirectory(path: String, extractRuntimeLibrary: Boolean) {
        require(path.isNotBlank()) { "mpv runtime directory must not be blank." }
        NativeRuntimeLoader.setRuntimeDirectory(
            runtime = CLASSPATH_RUNTIME,
            path = File(path),
            doExtract = extractRuntimeLibrary,
            validate = true,
        )
        runtimeConfigured = true
    }

    actual fun useDefaultRuntimeLibraryDirectory() {
        NativeRuntimeLoader.setRuntimeDirectory(
            runtime = CLASSPATH_RUNTIME,
            path = defaultRuntimeDirectory(),
            doExtract = true,
            validate = true,
        )
        runtimeConfigured = true
    }

    actual fun loadLibraries(context: Any?) {
        // Zero-configuration path: apps don't have to call prepareLibraries at all.
        // On the first MPVHandle creation, extract the classpath-bundled runtime
        // unless the app already configured a directory explicitly.
        if (runtimeConfigured) return
        synchronized(this) {
            if (runtimeConfigured) return
            if (classLoader.getResource(CLASSPATH_RUNTIME.manifestResourceName) == null) {
                error(mpvRuntimeMissingMessage(CLASSPATH_RUNTIME.manifestResourceName, currentPlatformSuffix()))
            }
            useDefaultRuntimeLibraryDirectory()
        }
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

internal fun currentPlatformSuffix(): String =
    platformSuffixFor(
        osName = System.getProperty("os.name").orEmpty(),
        osArch = System.getProperty("os.arch").orEmpty(),
    )

/** Maps `os.name`/`os.arch` to the runtime artifact suffix, e.g. `macos-arm64`. */
internal fun platformSuffixFor(osName: String, osArch: String): String {
    val os = when {
        osName.lowercase(Locale.ROOT).contains("win") -> "windows"
        osName.lowercase(Locale.ROOT).contains("mac") -> "macos"
        else -> "linux"
    }
    val arch = when (osArch.lowercase(Locale.ROOT)) {
        "aarch64", "arm64" -> "arm64"
        else -> "x64"
    }
    return "$os-$arch"
}

internal const val MPV_LEGACY_MANIFEST: String = "mpv-natives.txt"

/**
 * Runtime jars name their manifest per platform (`mpv-natives-<os>-<arch>.txt`) so that
 * multiple platforms' jars can coexist on the classpath (the `mediamp-mpv-runtime`
 * aggregator). Falls back to the legacy un-suffixed name for older runtime jars; when
 * nothing is on the classpath, keeps the platform-specific name for error messages.
 */
internal fun selectMpvManifestResource(
    platformSuffix: String,
    resourceExists: (String) -> Boolean,
): String {
    val platformSpecific = "mpv-natives-$platformSuffix.txt"
    return when {
        resourceExists(platformSpecific) -> platformSpecific
        resourceExists(MPV_LEGACY_MANIFEST) -> MPV_LEGACY_MANIFEST
        else -> platformSpecific
    }
}

internal fun mpvRuntimeMissingMessage(manifestResourceName: String, platformSuffix: String): String =
    "mpv native runtime not found: '$manifestResourceName' is not on the classpath. " +
            "Add runtimeOnly(\"org.openani.mediamp:mediamp-mpv-runtime\") (all platforms) or " +
            "runtimeOnly(\"org.openani.mediamp:mediamp-mpv-runtime-$platformSuffix\") (this platform only) " +
            "to your dependencies, or call MpvMediampPlayer.prepareLibraries(path) if you manage the native libraries yourself."
