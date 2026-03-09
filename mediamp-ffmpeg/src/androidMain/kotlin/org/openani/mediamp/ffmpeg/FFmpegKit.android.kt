/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.io.File

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
        return JvmFFmpegProcess.execute(runtime.ffmpegPath, args, runtime.environment)
    }

    public actual fun executeStreaming(args: List<String>): Flow<FFmpegOutputLine> {
        val runtime = resolveRuntime()
        return JvmFFmpegProcess.executeStreaming(runtime.ffmpegPath, args, runtime.environment)
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
            val ffmpegPath: String,
            val environment: Map<String, String>,
        )

        private fun resolveRuntime(): AndroidRuntime {
            val ctx = appContext
                ?: error("FFmpegKit.initialize(context) must be called before use.")
            val nativeLibDir = File(ctx.applicationInfo.nativeLibraryDir)
            // The ffmpeg binary is packaged as libffmpeg.so to satisfy Android's packaging rules
            val ffmpeg = nativeLibDir.resolve("libffmpeg.so")
            require(ffmpeg.exists()) {
                "libffmpeg.so not found in ${nativeLibDir.absolutePath}. " +
                        "Ensure mediamp-ffmpeg is included as a dependency."
            }
            if (!ffmpeg.canExecute()) {
                ffmpeg.setExecutable(true)
            }
            val linkerPath = buildString {
                append(nativeLibDir.absolutePath)
                val inherited = System.getenv("LD_LIBRARY_PATH")
                if (!inherited.isNullOrBlank()) {
                    append(':')
                    append(inherited)
                }
            }
            return AndroidRuntime(
                ffmpegPath = ffmpeg.absolutePath,
                environment = mapOf("LD_LIBRARY_PATH" to linkerPath),
            )
        }
    }
}
