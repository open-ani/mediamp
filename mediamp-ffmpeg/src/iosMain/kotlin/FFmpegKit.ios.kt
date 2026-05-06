/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * iOS implementation.
 *
 * Recognized operations (remux, probe) are executed via the thin libav* wrapper
 * to avoid global state pollution from repeated in-process main() calls.
 * Unrecognized or complex operations throw [UnsupportedOperationException]
 * because subprocess spawning is not available on iOS.
 */
public actual class FFmpegKit actual constructor() {

    public actual suspend fun execute(args: List<String>): FFmpegResult =
        withContext(Dispatchers.IO) {
            executionMutex.withLock {
                val parsed = ArgsParser.parse(args)
                if (parsed is MediaOperation.Remux || parsed is MediaOperation.Probe) {
                    try {
                        MediaTranscoder().execute(parsed)
                    } catch (e: Throwable) {
                        configuredLogHandler?.onLog(
                            FFmpegLogMessage(
                                16,
                                buildExceptionDebug(e, args),
                            ),
                        )
                        FFmpegResult(exitCode = 1)
                    }
                } else {
                    throw UnsupportedOperationException(
                        "Complex FFmpeg operations are not supported on iOS. " +
                            "Use Remux (-c copy) or Probe. Args: $args",
                    )
                }
            }
        }

    private fun buildExceptionDebug(throwable: Throwable, args: List<String>): String {
        return buildString {
            appendLine("FFmpegKit iOS execution threw exception")
            appendLine("command=ffmpeg ${args.joinToString(" ")}")
            appendLine("type=${throwable::class.qualifiedName}")
            appendLine("message=${throwable.message}")
            appendLine("stacktrace:")
            appendLine(throwable.stackTraceToString())
        }
    }

    public actual companion object {
        @Suppress("UNUSED_PARAMETER")
        public fun initialize(runtimeSearchPath: String) {
            // Apple runtime is linked at build time through MediampFFmpegKit.framework.
        }

        public actual fun setLogHandler(handler: FFmpegLogHandler?) {
            configuredLogHandler = handler
        }

        private val executionMutex: Mutex = Mutex()
        private var configuredLogHandler: FFmpegLogHandler? = null
    }
}
