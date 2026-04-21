/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
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
            loadRuntimeLibraries(runtimeLibrariesInLoadOrder(canonicalDir))
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
        val libraries = LibraryLoader::class.java.classLoader
            .getResourceAsStream("mpv-natives.txt")
            ?.bufferedReader()
            ?.readLines()
            ?.asSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.map { runtimeDir.resolve(it) }
            ?.filter { candidate -> candidate.isFile && candidate.isSharedRuntimeLibrary(osName) }
            ?.distinctBy(File::getAbsolutePath)
            ?.toList()
            ?: error(
                "mpv-natives.txt not found on classpath. " +
                    "Make sure mediamp-mpv-runtime-{os}-{arch} is on the classpath.",
            )

        require(libraries.any { it.name.equals(wrapperLibraryName(), ignoreCase = true) }) {
            "mpv-natives.txt does not list the MPV JNI wrapper '${wrapperLibraryName()}'."
        }
        return libraries
    }

    private fun loadRuntimeLibraries(libraries: List<File>) {
        if (!osName.contains("win")) {
            libraries.forEach { library -> System.load(library.absolutePath) }
            return
        }

        withWindowsDllDirectory(libraries.first().parentFile) {
            val remaining = libraries.toMutableList()
            val deferredFailures = linkedMapOf<String, UnsatisfiedLinkError>()

            while (remaining.isNotEmpty()) {
                var loadedAny = false
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val library = iterator.next()
                    try {
                        System.load(library.absolutePath)
                        deferredFailures.remove(library.absolutePath)
                        iterator.remove()
                        loadedAny = true
                    } catch (error: UnsatisfiedLinkError) {
                        if (error.isMissingWindowsDependency()) {
                            deferredFailures[library.absolutePath] = error
                        } else {
                            throw error
                        }
                    }
                }

                if (!loadedAny) {
                    val details = remaining.joinToString(separator = System.lineSeparator()) { library ->
                        val message = deferredFailures[library.absolutePath]?.message.orEmpty()
                        " - ${library.name}: $message"
                    }
                    throw UnsatisfiedLinkError(
                        "Failed to load Windows mpv runtime from ${libraries.first().parentFile.absolutePath}." +
                            System.lineSeparator() +
                            "Unresolved native libraries:" +
                            System.lineSeparator() +
                            details,
                    )
                }
            }
        }
    }

    private fun wrapperLibraryName(): String =
        when {
            osName.contains("win") -> "mediampv.dll"
            osName.contains("mac") -> "libmediampv.dylib"
            else -> "libmediampv.so"
        }
}

private inline fun <T> withWindowsDllDirectory(runtimeDir: File, block: () -> T): T {
    val configured = WindowsDllKernel32.INSTANCE.SetDllDirectoryW(WString(runtimeDir.absolutePath))
    check(configured) {
        "Failed to add Windows DLL search directory: ${runtimeDir.absolutePath}"
    }
    try {
        return block()
    } finally {
        WindowsDllKernel32.INSTANCE.SetDllDirectoryW(null)
    }
}

private fun File.isSharedRuntimeLibrary(osName: String): Boolean =
    when {
        osName.contains("win") -> name.endsWith(".dll", ignoreCase = true)
        osName.contains("mac") -> name.endsWith(".dylib", ignoreCase = true)
        else -> name.endsWith(".so") || name.contains(".so.")
    }

private fun UnsatisfiedLinkError.isMissingWindowsDependency(): Boolean {
    val text = message.orEmpty().lowercase(Locale.ROOT)
    return "can't find dependent libraries" in text ||
        "the specified module could not be found" in text ||
        "找不到指定的模块" in text ||
        "找不到依赖" in text
}

private interface WindowsDllKernel32 : StdCallLibrary {
    fun SetDllDirectoryW(pathName: WString?): Boolean

    companion object {
        val INSTANCE: WindowsDllKernel32 = Native.load(
            "kernel32",
            WindowsDllKernel32::class.java,
            W32APIOptions.DEFAULT_OPTIONS,
        ) as WindowsDllKernel32
    }
}
