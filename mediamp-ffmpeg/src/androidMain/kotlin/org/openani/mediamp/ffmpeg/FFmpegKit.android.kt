/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import android.content.Context
import java.io.File
import org.openani.mediamp.nativeloader.NativeClasspathRuntime
import org.openani.mediamp.nativeloader.NativeRuntimeLoader
import kotlin.error

/**
 * Android implementation.
 *
 * The preferred path is to load the JNI wrapper from the app's
 * `nativeLibraryDir` via `System.loadLibrary`, which lets Android's
 * linker resolve its FFmpeg dependencies in the standard way.
 */
public actual class FFmpegKit actual constructor() {

    public actual suspend fun execute(args: List<String>): FFmpegResult {
        val ctx = resolveRuntimeContext()
        JvmFFmpegProcess.initializeAndroidContext(ctx)

        return JvmFFmpegProcess.execute(args)
    }

    public actual companion object {
        private const val WRAPPER_NAME: String = "ffmpegkitjni"
        private val CLASSPATH_RUNTIME: NativeClasspathRuntime =
            NativeClasspathRuntime(
                libraryName = WRAPPER_NAME,
                manifestResourceName = "ffmpeg-natives.txt",
                resourceClassLoader = JvmFFmpegProcess::class.java.classLoader ?: ClassLoader.getSystemClassLoader(),
            )

        @Volatile
        private var appContext: Context? = null

        public actual fun setLogHandler(handler: FFmpegLogHandler?) {
            JvmFFmpegProcess.setLogHandler(handler)
        }

        public actual fun setRuntimeLibraryDirectory(path: String, extractRuntimeLibrary: Boolean) {
            error("Set custom runtime library directory is not allowed on Android.")
        }

        public actual fun useDefaultRuntimeLibraryDirectory() {
            NativeRuntimeLoader.setRuntimeDirectory(
                runtime = CLASSPATH_RUNTIME,
                path = File(
                    appContext?.applicationInfo?.nativeLibraryDir
                        ?: error("FFmpegKit.initialize(context) must be called before use."),
                ),
                doExtract = false,
                validate = false,
            )
        }

        /**
         * Initialize with application context. Must be called before any
         * [execute] calls.
         */
        @JvmStatic
        public fun initialize(context: Context) {
            appContext = context.applicationContext
            useDefaultRuntimeLibraryDirectory()
        }

        private fun resolveRuntimeContext(): Context =
            appContext ?: error("FFmpegKit.initialize(context) must be called before use.")
    }
}
