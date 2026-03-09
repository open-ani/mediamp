/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Desktop JVM implementation.
 *
 * The ffmpeg binary and shared libraries are expected on the classpath
 * (packaged in the `mediamp-ffmpeg-runtime-{os}-{arch}` JAR).
 * On first use they are extracted to a temporary directory.
 */
public actual class FFmpegKit actual constructor() {

    private val mutex = Mutex()

    @Volatile
    private var extractedDir: File? = null

    public actual suspend fun execute(args: List<String>): FFmpegResult {
        val ffmpeg = ensureExtracted()
        return JvmFFmpegProcess.execute(ffmpeg, args, libraryPathEnv())
    }

    public actual fun executeStreaming(args: List<String>): Flow<FFmpegOutputLine> {
        // Blocking extraction is acceptable here since the flow will run on IO dispatcher
        val ffmpeg = extractBlocking()
        return JvmFFmpegProcess.executeStreaming(ffmpeg, args, libraryPathEnv())
    }

    private suspend fun ensureExtracted(): String {
        extractedDir?.let { return ffmpegBinaryPath(it) }
        mutex.withLock {
            extractedDir?.let { return ffmpegBinaryPath(it) }
            val dir = extractNativeBinaries()
            extractedDir = dir
            return ffmpegBinaryPath(dir)
        }
    }

    private fun extractBlocking(): String {
        extractedDir?.let { return ffmpegBinaryPath(it) }
        val dir = extractNativeBinaries()
        extractedDir = dir
        return ffmpegBinaryPath(dir)
    }

    private fun libraryPathEnv(): Map<String, String> {
        val dir = extractedDir ?: return emptyMap()
        val key = when {
            OS_NAME.contains("win") -> "PATH"
            OS_NAME.contains("mac") -> "DYLD_LIBRARY_PATH"
            else -> "LD_LIBRARY_PATH"
        }
        // Prepend our dir so ffmpeg finds its shared libs
        val existing = System.getenv(key).orEmpty()
        val value = if (existing.isEmpty()) dir.absolutePath else "${dir.absolutePath}${File.pathSeparator}$existing"
        return mapOf(key to value)
    }

    private companion object {
        private val OS_NAME: String = System.getProperty("os.name").lowercase(Locale.ROOT)

        private fun ffmpegBinaryPath(dir: File): String {
            val name = if (OS_NAME.contains("win")) "ffmpeg.exe" else "ffmpeg"
            return dir.resolve(name).absolutePath
        }

        /**
         * Extract all native binaries from the classpath to a temp directory.
         * The runtime JAR contains files like `ffmpeg.exe`, `avcodec-62.dll`, etc.
         * at the root level.
         */
        private fun extractNativeBinaries(): File {
            val dir = Files.createTempDirectory("mediamp-ffmpeg").toFile()
            dir.deleteOnExit()

            val classLoader = FFmpegKit::class.java.classLoader
            // Read the manifest listing all native files
            val manifest = classLoader.getResourceAsStream("ffmpeg-natives.txt")
                ?.bufferedReader()?.readLines()
                ?: error(
                    "ffmpeg-natives.txt not found on classpath. " +
                            "Make sure mediamp-ffmpeg-runtime-{os}-{arch} is on the classpath.",
                )

            for (fileName in manifest) {
                if (fileName.isBlank()) continue
                val resource = classLoader.getResourceAsStream(fileName)
                    ?: continue
                val target = dir.resolve(fileName).toPath()
                resource.use { input ->
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                }
                // Make executable on Unix
                if (!OS_NAME.contains("win")) {
                    target.toFile().setExecutable(true)
                }
            }

            return dir
        }
    }
}
