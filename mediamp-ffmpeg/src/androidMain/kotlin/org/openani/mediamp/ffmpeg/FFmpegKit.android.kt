/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import android.content.Context
import org.openani.mediamp.nativeloader.NativeRuntimeLoader
import java.io.File

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
        val runtimeDir = File(ctx.applicationInfo.nativeLibraryDir)

        NativeRuntimeLoader.ensureRuntimeLoaded(runtimeDir, "ffmpegkitjni")
        JvmFFmpegProcess.initializeAndroidContext(ctx)

        return JvmFFmpegProcess.execute(args)
    }

    public actual companion object {
        @Volatile
        private var appContext: Context? = null

        public actual fun setLogHandler(handler: FFmpegLogHandler?) {
            JvmFFmpegProcess.setLogHandler(handler)
        }

        public actual fun setRuntimeLibraryDirectory(path: String, extractRuntimeLibrary: Boolean) {
        }

        public actual fun useDefaultRuntimeLibraryDirectory() {
        }

        /**
         * Initialize with application context. Must be called before any
         * [execute] calls.
         */
        @JvmStatic
        public fun initialize(context: Context) {
            appContext = context.applicationContext
        }

        private fun resolveRuntimeContext(): Context =
            appContext ?: error("FFmpegKit.initialize(context) must be called before use.")
    }
}
