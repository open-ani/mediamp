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
    private const val LEGACY_MANIFEST: String = "mpv-natives.txt"

    private val classLoader: ClassLoader =
        MPVHandle::class.java.classLoader ?: ClassLoader.getSystemClassLoader()

    /**
     * Runtime jars name their manifest per platform (`mpv-natives-<os>-<arch>.txt`) so that
     * multiple platforms' jars can coexist on the classpath (the `mediamp-mpv-runtime`
     * aggregator). Falls back to the legacy un-suffixed name for older runtime jars.
     */
    private val manifestResourceName: String = run {
        val platformSpecific = "mpv-natives-${currentPlatformSuffix()}.txt"
        when {
            classLoader.getResource(platformSpecific) != null -> platformSpecific
            classLoader.getResource(LEGACY_MANIFEST) != null -> LEGACY_MANIFEST
            else -> platformSpecific // nothing on classpath; keep the specific name for error messages
        }
    }

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
                error(
                    "mpv native runtime not found: '${CLASSPATH_RUNTIME.manifestResourceName}' is not on the classpath. " +
                            "Add runtimeOnly(\"org.openani.mediamp:mediamp-mpv-runtime\") (all platforms) or " +
                            "runtimeOnly(\"org.openani.mediamp:mediamp-mpv-runtime-${currentPlatformSuffix()}\") (this platform only) " +
                            "to your dependencies, or call MpvMediampPlayer.prepareLibraries(path) if you manage the native libraries yourself.",
                )
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

internal fun currentPlatformSuffix(): String {
    val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    val os = when {
        osName.contains("win") -> "windows"
        osName.contains("mac") -> "macos"
        else -> "linux"
    }
    val archName = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
    val arch = when (archName) {
        "aarch64", "arm64" -> "arm64"
        else -> "x64"
    }
    return "$os-$arch"
}
