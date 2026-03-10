/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared JVM implementation for running FFmpeg inside the current process via JNI.
 */
internal object JvmFFmpegProcess {
    private val executionLock = Any()
    private val loadMutex = Any()
    private val loadedRuntimeDirs = mutableSetOf<String>()
    private val osName: String = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)

    suspend fun execute(runtimeDir: File, args: List<String>, androidAppContext: Any? = null): FFmpegResult =
        withContext(Dispatchers.IO) {
            synchronized(executionLock) {
                ensureRuntimeLoaded(runtimeDir, androidAppContext)
                val stdoutFile = createOutputFile("stdout")
                val stderrFile = createOutputFile("stderr")
                try {
                    val exitCode = executeNative(args.toTypedArray(), stdoutFile.absolutePath, stderrFile.absolutePath)
                    FFmpegResult(
                        exitCode = exitCode,
                        stdout = if (stdoutFile.exists()) stdoutFile.readText() else "",
                        stderr = if (stderrFile.exists()) stderrFile.readText() else "",
                    )
                } finally {
                    stdoutFile.delete()
                    stderrFile.delete()
                }
            }
        }

    fun executeStreaming(
        runtimeDir: File,
        args: List<String>,
        androidAppContext: Any? = null,
    ): Flow<FFmpegOutputLine> = callbackFlow {
        val stdoutFile = createOutputFile("stdout")
        val stderrFile = createOutputFile("stderr")
        val failureRef = AtomicReference<Throwable?>()
        val completion = CountDownLatch(1)

        val runner = Thread {
            try {
                withBlockingExecution(runtimeDir, args, androidAppContext, stdoutFile, stderrFile)
            } catch (t: Throwable) {
                failureRef.set(t)
            } finally {
                completion.countDown()
            }
        }.apply {
            isDaemon = true
            start()
        }

        val stdoutTail = FileTail(stdoutFile, isError = false)
        val stderrTail = FileTail(stderrFile, isError = true)

        val poller = launch(Dispatchers.IO) {
            while (isActive) {
                stdoutTail.emitAvailableLines { trySend(it) }
                stderrTail.emitAvailableLines { trySend(it) }
                if (completion.remainingCount() == 0L) {
                    stdoutTail.emitRemaining { trySend(it) }
                    stderrTail.emitRemaining { trySend(it) }
                    break
                }
                delay(50)
            }

            val failure = failureRef.get()
            stdoutFile.delete()
            stderrFile.delete()
            if (failure != null) {
                close(failure)
            } else {
                close()
            }
        }

        awaitClose {
            poller.cancel()
            completion.await()
            stdoutFile.delete()
            stderrFile.delete()
        }
    }.flowOn(Dispatchers.IO)

    private fun withBlockingExecution(
        runtimeDir: File,
        args: List<String>,
        androidAppContext: Any?,
        stdoutFile: File,
        stderrFile: File,
    ) {
        synchronized(executionLock) {
            ensureRuntimeLoaded(runtimeDir, androidAppContext)
            executeNative(args.toTypedArray(), stdoutFile.absolutePath, stderrFile.absolutePath)
        }
    }

    private fun CountDownLatch.remainingCount(): Long = getCount()

    private fun ensureRuntimeLoaded(runtimeDir: File, androidAppContext: Any?) {
        val canonicalDir = runtimeDir.canonicalFile
        synchronized(loadMutex) {
            val key = canonicalDir.absolutePath
            if (key !in loadedRuntimeDirs) {
                runtimeLibrariesInLoadOrder(canonicalDir).forEach { library ->
                    System.load(library.absolutePath)
                }
                loadedRuntimeDirs += key
            }
            if (androidAppContext != null) {
                initializeAndroidContext(androidAppContext)
            }
        }
    }

    private fun runtimeLibrariesInLoadOrder(runtimeDir: File): List<File> {
        val wrapper = runtimeDir.resolve(wrapperLibraryName())
        require(wrapper.isFile) {
            "FFmpeg JNI runtime wrapper not found at ${wrapper.absolutePath}. Ensure the runtime artifact was packaged correctly."
        }

        val orderedLibraries = buildList {
            listOf(
                sharedLibraryName("avutil"),
                sharedLibraryName("swresample"),
                sharedLibraryName("swscale"),
                sharedLibraryName("avcodec"),
                sharedLibraryName("avformat"),
                sharedLibraryName("avfilter"),
                sharedLibraryName("avdevice"),
            ).forEach { libraryName ->
                runtimeDir.listFiles()
                    ?.firstOrNull { it.isFile && it.name.startsWith(libraryName) }
                    ?.let(::add)
            }

            runtimeDir.listFiles()
                ?.filter { candidate ->
                    candidate.isFile &&
                        candidate != wrapper &&
                        candidate.extension.equals(sharedLibraryExtension(), ignoreCase = true) &&
                        none { it.absolutePath == candidate.absolutePath }
                }
                ?.sortedBy { it.name }
                ?.let(::addAll)

            add(wrapper)
        }
        return orderedLibraries
    }

    private fun wrapperLibraryName(): String =
        when {
            osName.contains("win") -> "ffmpegkitjni.dll"
            osName.contains("mac") -> "libffmpegkitjni.dylib"
            else -> "libffmpegkitjni.so"
        }

    private fun sharedLibraryName(baseName: String): String =
        when {
            osName.contains("win") -> "$baseName."
            else -> "lib$baseName"
        }

    private fun sharedLibraryExtension(): String =
        when {
            osName.contains("win") -> "dll"
            osName.contains("mac") -> "dylib"
            else -> "so"
        }

    private fun createOutputFile(kind: String): File =
        Files.createTempFile("mediamp-ffmpeg-$kind-", ".log").toFile()

    @JvmStatic
    private external fun executeNative(args: Array<String>, stdoutPath: String, stderrPath: String): Int

    @JvmStatic
    private external fun initializeAndroidContext(appContext: Any)

    private class FileTail(
        private val file: File,
        private val isError: Boolean,
    ) {
        private var consumedChars: Int = 0
        private var pendingLineFragment: String = ""

        fun emitAvailableLines(emit: (FFmpegOutputLine) -> Unit) {
            val text = if (file.exists()) file.readText() else ""
            if (text.length < consumedChars) {
                consumedChars = 0
                pendingLineFragment = ""
            }
            if (text.length == consumedChars) return
            val delta = text.substring(consumedChars)
            consumedChars = text.length
            val combined = pendingLineFragment + delta
            val endsWithLineBreak = combined.endsWith('\n') || combined.endsWith('\r')
            val normalized = combined.replace("\r\n", "\n").replace('\r', '\n')
            val parts = normalized.split('\n')
            val completeCount = if (endsWithLineBreak) parts.size else parts.size - 1
            for (index in 0 until completeCount) {
                val line = parts[index]
                if (line.isNotEmpty()) {
                    emit(FFmpegOutputLine(line, isError))
                }
            }
            pendingLineFragment = if (endsWithLineBreak) "" else parts.lastOrNull().orEmpty()
        }

        fun emitRemaining(emit: (FFmpegOutputLine) -> Unit) {
            emitAvailableLines(emit)
            if (pendingLineFragment.isNotEmpty()) {
                emit(FFmpegOutputLine(pendingLineFragment, isError))
                pendingLineFragment = ""
            }
        }
    }
}
