/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openani.mediamp.ffmpeg.interop.ffmpegkit_execute
import org.openani.mediamp.ffmpeg.interop.ffmpegkit_set_log_callback
import platform.Foundation.NSLock

/**
 * iOS implementation.
 *
 * FFmpeg is linked into the bundled Apple framework at build time, so execution
 * can directly call the exported wrapper functions through Kotlin/Native cinterop.
 */
public actual class FFmpegKit actual constructor() {

    public actual suspend fun execute(args: List<String>): FFmpegResult =
        withContext(Dispatchers.IO) {
            executionMutex.withLock {
                try {
                    val exitCode = runInProcess(args)
                    val debugSuffix = if (exitCode == 0) "" else buildFailureDebug(args, exitCode)
                    if (debugSuffix.isNotEmpty()) {
                        configuredLogHandler?.onLog(FFmpegLogMessage(16, debugSuffix))
                    }
                    FFmpegResult(exitCode = exitCode)
                } catch (t: Throwable) {
                    configuredLogHandler?.onLog(FFmpegLogMessage(16, buildExceptionDebug(t, args)))
                    FFmpegResult(exitCode = 1)
                }
            }
        }

    @OptIn(ExperimentalForeignApi::class)
    private fun runInProcess(args: List<String>): Int = memScoped {
        val commandArgs = buildList {
            add("ffmpeg")
            addAll(args)
        }
        val collector = FFmpegLogLineCollector { message ->
            configuredLogHandler?.onLog(message)
        }
        withActiveLogCollector(collector) {
            ffmpegkit_set_log_callback(nativeLogCallback)
            try {
                val argv = allocArray<CPointerVar<ByteVar>>(commandArgs.size + 1)
                commandArgs.forEachIndexed { index, value ->
                    argv[index] = value.cstr.ptr
                }
                argv[commandArgs.size] = null
                ffmpegkit_execute(commandArgs.size, argv)
            } finally {
                ffmpegkit_set_log_callback(null)
            }
        }
    }

    private fun buildFailureDebug(args: List<String>, exitCode: Int): String {
        return buildString {
            append("\n[FFmpegKit debug]\n")
            append("binarySource=apple-framework\n")
            append("command=ffmpeg")
            if (args.isNotEmpty()) {
                append(' ')
                append(args.joinToString(" "))
            }
            append("\nexitCode=")
            append(exitCode)
            append('\n')
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

        public actual fun setRuntimeLibraryDirectory(path: String, extractRuntimeLibrary: Boolean) {
        }

        public actual fun useDefaultRuntimeLibraryDirectory() {
        }

        private fun withActiveLogCollector(collector: FFmpegLogLineCollector, block: () -> Int): Int {
            logCollectorLock.lock()
            activeLogCollector = collector
            logCollectorLock.unlock()
            return try {
                block()
            } finally {
                logCollectorLock.lock()
                try {
                    collector.flush()
                    activeLogCollector = null
                } finally {
                    logCollectorLock.unlock()
                }
            }
        }

        @OptIn(ExperimentalForeignApi::class)
        private val nativeLogCallback = staticCFunction<Int, CPointer<ByteVar>?, Unit> { level, message ->
            val text = message?.toKString().orEmpty()
            logCollectorLock.lock()
            try {
                activeLogCollector?.append(level, text)
            } finally {
                logCollectorLock.unlock()
            }
        }

        @OptIn(ExperimentalForeignApi::class)
        private val executionMutex: Mutex = Mutex()
        private var configuredLogHandler: FFmpegLogHandler? = null
        private val logCollectorLock: NSLock = NSLock()
        private var activeLogCollector: FFmpegLogLineCollector? = null
    }
}
