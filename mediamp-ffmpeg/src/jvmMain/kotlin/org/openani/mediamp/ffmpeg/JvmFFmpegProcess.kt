/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared JVM implementation for running FFmpeg inside the current process via JNI.
 */
internal object JvmFFmpegProcess {
    private val executionLock = Any()
    private val logDispatchLock = Any()
    private var configuredLogHandler: FFmpegLogHandler? = null
    private var activeLogCollector: FFmpegLogLineCollector? = null

    suspend fun execute(
        args: List<String>,
    ): FFmpegResult = withContext(Dispatchers.IO) {
        synchronized(executionLock) {
            val collector = FFmpegLogLineCollector { message ->
                configuredLogHandler?.onLog(message)
            }
            val exitCode = withActiveLogCollector(collector) {
                executeNative(args.toTypedArray())
            }
            FFmpegResult(exitCode = exitCode)
        }
    }

    fun setLogHandler(handler: FFmpegLogHandler?) {
        synchronized(logDispatchLock) {
            configuredLogHandler = handler
        }
    }

    private inline fun <T> withActiveLogCollector(
        collector: FFmpegLogLineCollector,
        block: () -> T,
    ): T {
        synchronized(logDispatchLock) {
            activeLogCollector = collector
        }
        try {
            return block()
        } finally {
            synchronized(logDispatchLock) {
                collector.flush()
                activeLogCollector = null
            }
        }
    }

    @JvmStatic
    private external fun executeNative(args: Array<String>): Int

    @JvmStatic
    internal external fun initializeAndroidContext(appContext: Any)

    @JvmStatic
    fun onNativeLog(level: Int, message: String) {
        synchronized(logDispatchLock) {
            activeLogCollector?.append(level, message)
        }
    }
}
