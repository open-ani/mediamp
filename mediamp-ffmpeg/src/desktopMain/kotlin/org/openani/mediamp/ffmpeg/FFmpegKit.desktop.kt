/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg
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
    public actual companion object {
        public actual fun setLogHandler(handler: FFmpegLogHandler?) {
            JvmFFmpegProcess.setLogHandler(handler)
        }

        private val OS_NAME: String = System.getProperty("os.name").lowercase(Locale.ROOT)
        private val extractionMutex: Mutex = Mutex()

        @Volatile
        private var extractedDir: File? = null

        /**
         * Extract all native binaries from the classpath to a temp directory.
         * The runtime JAR contains files like `libffmpegkitjni.dylib`, `libavcodec.62.dylib`, etc.
         * at the root level.
         */
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
                val resource = classLoader.getResourceAsStream(fileName)
                    ?: error("Native runtime file '$fileName' listed in ffmpeg-natives.txt was not found on the classpath.")
                val targetFile = dir.resolve(fileName)
                targetFile.parentFile?.mkdirs()
                val target = targetFile.toPath()
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

    public actual suspend fun execute(args: List<String>): FFmpegResult {
        val runtimeDir = ensureExtracted()
        return JvmFFmpegProcess.execute(runtimeDir, args)
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

}
