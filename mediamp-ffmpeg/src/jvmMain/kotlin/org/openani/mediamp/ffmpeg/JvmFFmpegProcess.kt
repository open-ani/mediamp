/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Shared JVM implementation for running FFmpeg inside the current process via JNI.
 */
internal object JvmFFmpegProcess {
    private val executionLock = Any()
    private val loadMutex = Any()
    private val logDispatchLock = Any()
    private val loadedRuntimeDirs = mutableSetOf<String>()
    private val osName: String = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    private var configuredLogHandler: FFmpegLogHandler? = null
    private var activeLogCollector: FFmpegLogLineCollector? = null

    suspend fun execute(runtimeDir: File, args: List<String>, androidAppContext: Any? = null): FFmpegResult =
        withContext(Dispatchers.IO) {
            synchronized(executionLock) {
                ensureRuntimeLoaded(runtimeDir, androidAppContext)
                val collector = FFmpegLogLineCollector { message ->
                    configuredLogHandler?.onLog(message)
                }
                val exitCode = withActiveLogCollector(collector) {
                    executeNative(args.toTypedArray())
                }
                FFmpegResult(exitCode = exitCode)
            }
        }

    private fun ensureRuntimeLoaded(runtimeDir: File, androidAppContext: Any?) {
        val canonicalDir = runtimeDir.canonicalFile
        synchronized(loadMutex) {
            val key = canonicalDir.absolutePath
            if (key !in loadedRuntimeDirs) {
                runtimeLibrariesInLoadOrder(canonicalDir).forEach { library ->
                    System.load(library.absolutePath)
                }
                loadedRuntimeDirs += key
            }
            if (androidAppContext != null) {
                initializeAndroidContext(androidAppContext)
            }
        }
    }

    private fun runtimeLibrariesInLoadOrder(runtimeDir: File): List<File> {
        val wrapper = runtimeDir.resolve(wrapperLibraryName())
        require(wrapper.isFile) {
            "FFmpeg JNI runtime wrapper not found at ${wrapper.absolutePath}. Ensure the runtime artifact was packaged correctly."
        }

        val allSharedLibraries = runtimeDir.listFiles()
            ?.filter { candidate ->
                candidate.isFile &&
                        candidate != wrapper &&
                        candidate.extension.equals(sharedLibraryExtension(), ignoreCase = true)
            }
            .orEmpty()
        val ffmpegLibraryPrefixes = listOf(
            sharedLibraryName("avutil"),
            sharedLibraryName("swresample"),
            sharedLibraryName("swscale"),
            sharedLibraryName("avcodec"),
            sharedLibraryName("avformat"),
            sharedLibraryName("avfilter"),
            sharedLibraryName("avdevice"),
        )

        val orderedLibraries = buildList {
            if (osName.contains("win")) {
                // Windows FFmpeg DLLs depend on the toolchain runtime DLLs we package
                // alongside them (for example libwinpthread/libgcc). Load those first,
                // in reverse-alphabetical order so that leaf dependencies (libwinpthread)
                // are loaded before their dependents (libgcc_s_seh) – this prevents the
                // OS from picking up an incompatible version from the system PATH.
                allSharedLibraries
                    .filter { candidate -> ffmpegLibraryPrefixes.none(candidate.name::startsWith) }
                    .sortedByDescending { it.name }
                    .let(::addAll)
            }

            ffmpegLibraryPrefixes.forEach { libraryName ->
                allSharedLibraries
                    .firstOrNull { candidate -> candidate.name.startsWith(libraryName) }
                    ?.let(::add)
            }

            allSharedLibraries
                .filter { candidate -> none { it.absolutePath == candidate.absolutePath } }
                .sortedBy { it.name }
                .let(::addAll)

            add(wrapper)
        }
        return orderedLibraries
    }

    private fun wrapperLibraryName(): String =
        when {
            osName.contains("win") -> "ffmpegkitjni.dll"
            osName.contains("mac") -> "libffmpegkitjni.dylib"
            else -> "libffmpegkitjni.so"
        }

    private fun sharedLibraryName(baseName: String): String =
        when {
            osName.contains("win") -> "$baseName-"
            else -> "lib$baseName"
        }

    private fun sharedLibraryExtension(): String =
        when {
            osName.contains("win") -> "dll"
            osName.contains("mac") -> "dylib"
            else -> "so"
        }

    fun setLogHandler(handler: FFmpegLogHandler?) {
        synchronized(logDispatchLock) {
            configuredLogHandler = handler
        }
    }

    private inline fun <T> withActiveLogCollector(
        collector: FFmpegLogLineCollector,
        block: () -> T,
    ): T {
        synchronized(logDispatchLock) {
            activeLogCollector = collector
        }
        try {
            return block()
        } finally {
            synchronized(logDispatchLock) {
                collector.flush()
                activeLogCollector = null
            }
        }
    }

    @JvmStatic
    private external fun executeNative(args: Array<String>): Int

    @JvmStatic
    private external fun initializeAndroidContext(appContext: Any)

    @JvmStatic
    fun onNativeLog(level: Int, message: String) {
        synchronized(logDispatchLock) {
            activeLogCollector?.append(level, message)
        }
    }
}
