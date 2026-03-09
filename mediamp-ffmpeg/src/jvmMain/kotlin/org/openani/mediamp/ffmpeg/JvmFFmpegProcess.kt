/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File

/**
 * Shared JVM implementation for running the ffmpeg process.
 */
internal object JvmFFmpegProcess {

    suspend fun execute(ffmpegPath: String, args: List<String>, env: Map<String, String> = emptyMap()): FFmpegResult =
        withContext(Dispatchers.IO) {
            val process = startProcess(ffmpegPath, args, env)
            // FFmpeg writes progress/info to stderr; stdout is rarely used unless piping
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            FFmpegResult(exitCode, stdout, stderr)
        }

    fun executeStreaming(
        ffmpegPath: String,
        args: List<String>,
        env: Map<String, String> = emptyMap(),
    ): Flow<FFmpegOutputLine> = callbackFlow {
        val process = startProcess(ffmpegPath, args, env)

        // Launch readers for both streams
        val stdoutReader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!isClosedForSend) trySend(FFmpegOutputLine(line, isError = false))
                }
            }
        }.apply { isDaemon = true; start() }

        val stderrReader = Thread {
            process.errorStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!isClosedForSend) trySend(FFmpegOutputLine(line, isError = true))
                }
            }
        }.apply { isDaemon = true; start() }

        stdoutReader.join()
        stderrReader.join()
        process.waitFor()
        close()
    }.flowOn(Dispatchers.IO)

    private fun startProcess(ffmpegPath: String, args: List<String>, env: Map<String, String>): Process {
        val command = buildList {
            add(ffmpegPath)
            addAll(args)
        }
        val pb = ProcessBuilder(command)
            .redirectErrorStream(false)
        env.forEach { (k, v) -> pb.environment()[k] = v }
        return pb.start()
    }
}
