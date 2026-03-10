/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.zip.ZipFile

/**
 * Android implementation.
 *
 * The ffmpeg binary is bundled in the APK's native libs directory
 * (`lib/{abi}/libffmpeg.so` — renamed with `lib` prefix and `.so` suffix
 * so the Android packaging pipeline includes it).
 *
 * Shared libraries (libavcodec.so, etc.) are also in the native libs
 * directory and are automatically available via the system linker.
 */
public actual class FFmpegKit actual constructor() {

    public actual suspend fun execute(args: List<String>): FFmpegResult {
        val runtime = resolveRuntime()
        return JvmFFmpegProcess.execute(runtime.runtimeDir, args, runtime.appContext)
    }

    public actual fun executeStreaming(args: List<String>): Flow<FFmpegOutputLine> {
        val runtime = resolveRuntime()
        return JvmFFmpegProcess.executeStreaming(runtime.runtimeDir, args, runtime.appContext)
    }

    public companion object {
        @Volatile
        private var appContext: Context? = null

        /**
         * Initialize with application context. Must be called before any
         * [execute] or [executeStreaming] calls.
         */
        @JvmStatic
        public fun initialize(context: Context) {
            appContext = context.applicationContext
        }

        private data class AndroidRuntime(
            val runtimeDir: File,
            val appContext: Context,
        )

        private fun resolveRuntime(): AndroidRuntime {
            val ctx = appContext
                ?: error("FFmpegKit.initialize(context) must be called before use.")
            val runtimeDir = resolveRuntimeDirectory(ctx)
            return AndroidRuntime(
                runtimeDir = runtimeDir,
                appContext = ctx,
            )
        }

        private fun resolveRuntimeDirectory(context: Context): File {
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            if (nativeLibDir.resolve("libffmpegkitjni.so").exists()) {
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
                "libffmpeg.so not found in ${appInfo.nativeLibraryDir}. " +
                    "No packaged FFmpeg runtime was found in ${apkFiles.joinToString { it.absolutePath }}. " +
                    "Ensure mediamp-ffmpeg is included as a dependency.",
            )

            val runtimeDir = context.codeCacheDir
                .resolve("mediamp-ffmpeg")
                .resolve(abi)
                .apply { mkdirs() }

            val wrapper = runtimeDir.resolve("libffmpegkitjni.so")
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
                "libffmpegkitjni.so not found in ${appInfo.nativeLibraryDir}. " +
                    "Failed to extract FFmpeg runtime for ABI $abi from ${apkFiles.joinToString { it.absolutePath }}."
            }
            return runtimeDir
        }

        private fun apkContainsRuntimeForAbi(apk: File, abi: String): Boolean =
            ZipFile(apk).use { zip ->
                zip.getEntry("lib/$abi/libffmpegkitjni.so") != null
            }
    }
}
