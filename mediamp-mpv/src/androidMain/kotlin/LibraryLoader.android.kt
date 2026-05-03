/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import android.content.Context
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

internal actual object LibraryLoader {
    private val loadLock = Any()
    private val loadedRuntimeDirs = mutableSetOf<String>()

    actual fun loadLibraries(context: Any?) {
        val appContext = (context as? Context)?.applicationContext
            ?: error("Android mpv loading requires an android.content.Context.")
        val runtimeDir = resolveRuntimeDirectory(appContext)
        ensureRuntimeLoaded(runtimeDir)
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

    private fun resolveRuntimeDirectory(context: Context): File {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        if (nativeLibDir.resolve("libmediampv.so").exists()) {
            return nativeLibDir
        }
        return extractRuntimeFromInstalledApks(context)
    }

    @Synchronized
    private fun extractRuntimeFromInstalledApks(context: Context): File {
        val appInfo = context.applicationInfo
        val apkFiles = buildList {
            add(appInfo.sourceDir)
            appInfo.splitSourceDirs?.let(::addAll)
        }.map(::File)
            .filter(File::exists)

        val abi = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { candidate ->
            if (apkFiles.any { apkContainsRuntimeForAbi(it, candidate) }) candidate else null
        } ?: error(
            "libmediampv.so not found in ${appInfo.nativeLibraryDir}. " +
                "No packaged mpv runtime was found in ${apkFiles.joinToString { it.absolutePath }}. " +
                "Ensure mediamp-mpv is included as a dependency.",
        )

        val runtimeDir = context.codeCacheDir
            .resolve("mediamp-mpv")
            .resolve(abi)
            .apply { mkdirs() }

        val wrapper = runtimeDir.resolve("libmediampv.so")
        if (wrapper.exists()) {
            return runtimeDir
        }

        val prefix = "lib/$abi/"
        var extractedAny = false
        apkFiles.forEach { apk ->
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith(prefix) && it.name.endsWith(".so") }
                    .forEach { entry ->
                        extractedAny = true
                        val fileName = entry.name.substringAfterLast('/')
                        val output = runtimeDir.resolve(fileName)
                        zip.getInputStream(entry).use { input ->
                            output.outputStream().use { outputStream ->
                                input.copyTo(outputStream)
                            }
                        }
                        output.setReadable(true, false)
                        output.setExecutable(true, false)
                    }
            }
        }

        require(extractedAny && wrapper.exists()) {
            "libmediampv.so not found in ${appInfo.nativeLibraryDir}. " +
                "Failed to extract mpv runtime for ABI $abi from ${apkFiles.joinToString { it.absolutePath }}."
        }
        return runtimeDir
    }

    private fun apkContainsRuntimeForAbi(apk: File, abi: String): Boolean =
        ZipFile(apk).use { zip ->
            zip.getEntry("lib/$abi/libmediampv.so") != null
        }

    private fun runtimeLibrariesInLoadOrder(runtimeDir: File): List<File> {
        val wrapper = runtimeDir.resolve("libmediampv.so")
        require(wrapper.isFile) {
            "MPV JNI wrapper not found at ${wrapper.absolutePath}. Ensure the runtime artifact was packaged correctly."
        }

        val allSharedLibraries = runtimeDir.listFiles()
            ?.filter { it.isFile && it != wrapper && it.name.endsWith(".so") }
            .orEmpty()

        val supportLibraries = listOf(
            "libc++_shared.so",
            "libz.so",
            "libpng16.so",
            "libfreetype.so",
            "libfribidi.so",
            "libharfbuzz-raster.so",
            "libharfbuzz-subset.so",
            "libharfbuzz-vector.so",
            "libharfbuzz.so",
            "libass.so",
        )
        val ffmpegLibraries = listOf(
            "libavutil.so",
            "libswresample.so",
            "libswscale.so",
            "libavcodec.so",
            "libavformat.so",
            "libavfilter.so",
            "libavdevice.so",
        )
        val mpvLibraries = listOf(
            "libmpv.so",
        )

        return buildList {
            supportLibraries.forEach { libraryName ->
                allSharedLibraries.firstOrNull { it.name == libraryName }?.let(::add)
            }
            ffmpegLibraries.forEach { libraryName ->
                allSharedLibraries.firstOrNull { it.name == libraryName }?.let(::add)
            }
            mpvLibraries.forEach { libraryName ->
                allSharedLibraries.firstOrNull { it.name == libraryName }?.let(::add)
            }
            allSharedLibraries
                .filter { candidate -> none { it.absolutePath == candidate.absolutePath } }
                .sortedBy(File::getName)
                .let(::addAll)
            add(wrapper)
        }
    }
}
