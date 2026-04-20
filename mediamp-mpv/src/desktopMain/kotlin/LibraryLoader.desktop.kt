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
import java.nio.file.StandardCopyOption
import java.util.Locale

internal actual object LibraryLoader {
    private val osName: String = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    private val extractionLock = Any()
    private val loadLock = Any()

    @Volatile
    private var extractedDir: File? = null

    private val loadedRuntimeDirs = mutableSetOf<String>()

    actual fun loadLibraries(context: Any?) {
        val runtimeDir = ensureExtracted()
        ensureRuntimeLoaded(runtimeDir)
    }

    private fun ensureExtracted(): File {
        extractedDir?.let { return it }
        synchronized(extractionLock) {
            extractedDir?.let { return it }
            val dir = extractNativeBinaries()
            extractedDir = dir
            return dir
        }
    }

    private fun ensureRuntimeLoaded(runtimeDir: File) {
        val canonicalDir = runtimeDir.canonicalFile
        synchronized(loadLock) {
            val key = canonicalDir.absolutePath
            if (key in loadedRuntimeDirs) return
            runtimeLibrariesInLoadOrder(canonicalDir).forEach { library ->
                System.load(library.absolutePath)
            }
            loadedRuntimeDirs += key
        }
    }

    private fun extractNativeBinaries(): File {
        val dir = Files.createTempDirectory("mediamp-mpv").toFile()
        dir.deleteOnExit()

        val classLoader = LibraryLoader::class.java.classLoader
        val manifest = classLoader.getResourceAsStream("mpv-natives.txt")
            ?.bufferedReader()
            ?.readLines()
            ?: error(
                "mpv-natives.txt not found on classpath. " +
                    "Make sure mediamp-mpv-runtime-{os}-{arch} is on the classpath.",
            )

        manifest.forEach { fileName ->
            if (fileName.isBlank()) return@forEach
            val resource = classLoader.getResourceAsStream(fileName)
                ?: error("Native runtime file '$fileName' listed in mpv-natives.txt was not found on the classpath.")
            val target = dir.resolve(fileName)
            target.parentFile?.mkdirs()
            resource.use { input ->
                Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (!osName.contains("win")) {
                target.setReadable(true, false)
                target.setExecutable(true, false)
            }
        }
        return dir
    }

    private fun runtimeLibrariesInLoadOrder(runtimeDir: File): List<File> {
        val wrapper = runtimeDir.resolve(wrapperLibraryName())
        require(wrapper.isFile) {
            "MPV JNI wrapper not found at ${wrapper.absolutePath}. Ensure the runtime artifact was packaged correctly."
        }

        val allSharedLibraries = runtimeDir.listFiles()
            ?.filter { candidate ->
                candidate.isFile &&
                    candidate != wrapper &&
                    candidate.isSharedRuntimeLibrary(osName)
            }
            .orEmpty()

        val ffmpegPrefixes = listOf(
            "avutil-",
            "swresample-",
            "swscale-",
            "avcodec-",
            "avformat-",
            "avfilter-",
            "avdevice-",
        )
        val mpvPrefixes = listOf(
            "libmpv",
            "libass",
            "libplacebo",
        )

        return buildList {
            if (osName.contains("win")) {
                allSharedLibraries
                    .filterNot { candidate ->
                        ffmpegPrefixes.any(candidate.name::startsWith) ||
                            mpvPrefixes.any(candidate.name::startsWith)
                    }
                    .sortedByDescending(File::getName)
                    .let(::addAll)
            } else {
                allSharedLibraries
                    .filterNot { candidate ->
                        ffmpegPrefixes.any(candidate.name::startsWith) ||
                            mpvPrefixes.any(candidate.name::startsWith)
                    }
                    .sortedBy(File::getName)
                    .let(::addAll)
            }

            ffmpegPrefixes.forEach { prefix ->
                allSharedLibraries
                    .filter { it.name.startsWith(prefix) }
                    .maxWithOrNull(compareBy<File>({ it.name.length }, { it.name }))
                    ?.let(::add)
            }

            mpvPrefixes.forEach { prefix ->
                allSharedLibraries
                    .filter { it.name.startsWith(prefix) }
                    .maxWithOrNull(compareBy<File>({ it.name.length }, { it.name }))
                    ?.let(::add)
            }

            allSharedLibraries
                .filter { candidate -> none { it.absolutePath == candidate.absolutePath } }
                .sortedBy(File::getName)
                .let(::addAll)

            add(wrapper)
        }
    }

    private fun wrapperLibraryName(): String =
        when {
            osName.contains("win") -> "mediampv.dll"
            osName.contains("mac") -> "libmediampv.dylib"
            else -> "libmediampv.so"
        }
}

private fun File.isSharedRuntimeLibrary(osName: String): Boolean =
    when {
        osName.contains("win") -> name.endsWith(".dll", ignoreCase = true)
        osName.contains("mac") -> name.endsWith(".dylib", ignoreCase = true)
        else -> name.endsWith(".so") || name.contains(".so.")
    }
