/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Desktop JVM implementation.
 *
 * Recognized operations (remux, probe) are executed via the thin libav* wrapper
 * to avoid global state pollution from repeated in-process main() calls.
 * Unrecognized or complex operations fall back to spawning the extracted ffmpeg
 * binary as a subprocess.
 */
public actual class FFmpegKit actual constructor() {
    public actual suspend fun execute(args: List<String>): FFmpegResult {
        val parsed = ArgsParser.parse(args)
        if (parsed is MediaOperation.Remux || parsed is MediaOperation.Probe) {
            return try {
                MediaTranscoder().execute(parsed)
            } catch (e: Throwable) {
                FFmpegResult(exitCode = 1)
            }
        }
        val runtimeDir = ensureExtracted()
        return executeSubprocess(runtimeDir, args)
    }

    public actual companion object {
        public actual fun setLogHandler(handler: FFmpegLogHandler?) {
            configuredLogHandler = handler
        }

        private val OS_NAME: String = System.getProperty("os.name").lowercase(Locale.ROOT)
        private val extractionMutex: Mutex = Mutex()

        @Volatile
        private var extractedDir: File? = null

        private var configuredLogHandler: FFmpegLogHandler? = null

        private fun extractNativeBinaries(): File {
            val dir = Files.createTempDirectory("mediamp-ffmpeg").toFile()
            dir.deleteOnExit()

            val classLoader = FFmpegKit::class.java.classLoader
            val manifest = classLoader.getResourceAsStream("ffmpeg-natives.txt")
                ?.bufferedReader()?.readLines()
                ?: error(
                    "ffmpeg-natives.txt not found on classpath. " +
                        "Make sure mediamp-ffmpeg-runtime-{os}-{arch} is on the classpath.",
                )

            for (fileName in manifest) {
                if (fileName.isBlank()) continue
                val resource = classLoader.getResourceAsStream(fileName) ?: continue
                val target = dir.resolve(fileName).toPath()
                resource.use { input ->
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                }
                if (!OS_NAME.contains("win")) {
                    target.toFile().setExecutable(true)
                }
            }

            return dir
        }
    }

    private suspend fun ensureExtracted(): File {
        extractedDir?.let { return it }
        extractionMutex.withLock {
            extractedDir?.let { return it }
            val dir = extractNativeBinaries()
            extractedDir = dir
            return dir
        }
    }

    private suspend fun executeSubprocess(runtimeDir: File, args: List<String>): FFmpegResult =
        withContext(Dispatchers.IO) {
            val binaryName = if (OS_NAME.contains("win")) "ffmpeg.exe" else "ffmpeg"
            val binary = runtimeDir.resolve(binaryName)
            if (!binary.exists()) {
                // Fall back to legacy JNI wrapper if binary not packaged
                return@withContext JvmFFmpegProcess.execute(runtimeDir, args)
            }

            val process = ProcessBuilder(listOf(binary.absolutePath) + args)
                .redirectErrorStream(true)
                .start()

            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    configuredLogHandler?.onLog(FFmpegLogMessage(32, line!!))
                }
            }

            val exitCode = process.waitFor()
            FFmpegResult(exitCode = exitCode)
        }
}
