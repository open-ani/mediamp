/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import org.openani.mediamp.nativeloader.NativeClasspathRuntime
import org.openani.mediamp.nativeloader.NativeRuntimeLoader
import java.io.File

/**
 * Desktop JVM implementation.
 *
 * The FFmpeg JNI wrapper and shared libraries are expected on the classpath
 * (packaged in the `mediamp-ffmpeg-runtime-{os}-{arch}` JAR).
 * Callers must explicitly choose either a custom runtime directory or the
 * default temporary extraction directory before first use.
 */
public actual class FFmpegKit actual constructor() {
    public actual companion object {
        private const val WRAPPER_NAME: String = "ffmpegkitjni"
        private val CLASSPATH_RUNTIME: NativeClasspathRuntime =
            NativeClasspathRuntime(
                wrapperName = WRAPPER_NAME,
                manifestResourceName = "ffmpeg-natives.txt",
                temporaryDirectoryPrefix = "mediamp-ffmpeg",
                resourceClassLoader = JvmFFmpegProcess::class.java.classLoader,
            )

        public actual fun setLogHandler(handler: FFmpegLogHandler?) {
            JvmFFmpegProcess.setLogHandler(handler)
        }

        public actual fun setRuntimeLibraryDirectory(path: String, extractRuntimeLibrary: Boolean) {
            require(path.isNotBlank()) { "FFmpeg runtime directory must not be blank." }
            NativeRuntimeLoader.setRuntimeDirectory(WRAPPER_NAME, File(path), extractRuntimeLibrary)
        }

        public actual fun useDefaultRuntimeLibraryDirectory() {
            NativeRuntimeLoader.useDefaultRuntimeDirectory(WRAPPER_NAME)
        }
    }

    public actual suspend fun execute(args: List<String>): FFmpegResult {
        NativeRuntimeLoader.ensureConfiguredClasspathRuntimeLoaded(CLASSPATH_RUNTIME)
        return JvmFFmpegProcess.execute(args)
    }
}
