/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.O_TRUNC
import platform.posix.RTLD_GLOBAL
import platform.posix.RTLD_LOCAL
import platform.posix.RTLD_NOW
import platform.posix.STDERR_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.close
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym
import platform.posix.dup
import platform.posix.dup2
import platform.posix.errno
import platform.posix.fflush
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.open
import platform.posix.read
import platform.posix.setenv
import platform.posix.strerror
import platform.posix.unlink
import kotlin.random.Random

/**
 * iOS implementation.
 *
 * B-scheme: invoke FFmpeg in-process via a wrapper dylib symbol
 * (`ffmpegkit_execute`) instead of spawning an executable.
 */
public actual class FFmpegKit actual constructor() {

    public actual suspend fun execute(args: List<String>): FFmpegResult =
        withContext(Dispatchers.IO) {
            executionMutex.withLock {
                try {
                    val runtime = resolveRuntimeLocation()
                    configureDyld(runtime.runtimeDir)
                    val preloadFailures = preloadRuntimeLibraries(runtime.runtimeDir)
                    val runResult = runInProcess(runtime, args)
                    val debugSuffix = if (runResult.exitCode == 0) {
                        ""
                    } else {
                        buildFailureDebug(runtime, args, runResult.exitCode, preloadFailures)
                    }
                    FFmpegResult(
                        exitCode = runResult.exitCode,
                        stdout = runResult.stdout + debugSuffix,
                        stderr = runResult.stderr,
                    )
                } catch (t: Throwable) {
                    FFmpegResult(
                        exitCode = 1,
                        stdout = "",
                        stderr = buildExceptionDebug(t, args),
                    )
                }
            }
        }

    public actual fun executeStreaming(args: List<String>): Flow<FFmpegOutputLine> = flow {
        val result = execute(args)
        result.stdout.lineSequence().forEach { line ->
            if (line.isNotBlank()) {
                emit(FFmpegOutputLine(line, isError = false))
            }
        }
        result.stderr.lineSequence().forEach { line ->
            if (line.isNotBlank()) {
                emit(FFmpegOutputLine(line, isError = true))
            }
        }
    }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalForeignApi::class)
    private fun runInProcess(location: RuntimeLocation, args: List<String>): RunResult = memScoped {
        val commandArgs = buildList {
            add("ffmpeg")
            addAll(args)
        }

        val stdoutPath = createCapturePath(location.runtimeDir, "stdout")
        val stderrPath = createCapturePath(location.runtimeDir, "stderr")

        val stdoutFd = open(stdoutPath, O_CREAT or O_TRUNC or O_RDWR, 0x180)
        if (stdoutFd < 0) {
            return@memScoped RunResult(
                exitCode = -1,
                stdout = "",
                stderr = "open($stdoutPath) failed: errno=$errno (${strerror(errno)?.toKString() ?: "unknown"})\n",
            )
        }
        val stderrFd = open(stderrPath, O_CREAT or O_TRUNC or O_RDWR, 0x180)
        if (stderrFd < 0) {
            close(stdoutFd)
            return@memScoped RunResult(
                exitCode = -1,
                stdout = "",
                stderr = "open($stderrPath) failed: errno=$errno (${strerror(errno)?.toKString() ?: "unknown"})\n",
            )
        }

        val savedStdout = dup(STDOUT_FILENO)
        val savedStderr = dup(STDERR_FILENO)
        if (savedStdout < 0 || savedStderr < 0) {
            if (savedStdout >= 0) close(savedStdout)
            if (savedStderr >= 0) close(savedStderr)
            close(stdoutFd)
            close(stderrFd)
            return@memScoped RunResult(
                exitCode = -1,
                stdout = "",
                stderr = "dup() failed: errno=$errno (${strerror(errno)?.toKString() ?: "unknown"})\n",
            )
        }

        var exitCode = -1
        var redirectError: String? = null
        try {
            if (dup2(stdoutFd, STDOUT_FILENO) < 0 || dup2(stderrFd, STDERR_FILENO) < 0) {
                redirectError = "dup2() failed: errno=$errno (${strerror(errno)?.toKString() ?: "unknown"})\n"
            } else {
                val executeFn = loadOrResolveEntryPoint(location.wrapperPath)
                val argv = allocArray<CPointerVar<ByteVar>>(commandArgs.size + 1)
                commandArgs.forEachIndexed { index, value ->
                    argv[index] = value.cstr.ptr
                }
                argv[commandArgs.size] = null
                exitCode = executeFn(commandArgs.size, argv)
            }
        } finally {
            fflush(null)
            dup2(savedStdout, STDOUT_FILENO)
            dup2(savedStderr, STDERR_FILENO)
            close(savedStdout)
            close(savedStderr)
            close(stdoutFd)
            close(stderrFd)
        }

        val stdout = readCaptureFile(stdoutPath)
        val stderr = readCaptureFile(stderrPath)
        unlink(stdoutPath)
        unlink(stderrPath)

        if (redirectError != null) {
            return@memScoped RunResult(
                exitCode = -1,
                stdout = stdout,
                stderr = stderr + redirectError,
            )
        }
        return@memScoped RunResult(exitCode, stdout, stderr)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun configureDyld(runtimeDir: String) {
        setenv("DYLD_LIBRARY_PATH", runtimeDir, 1)
        setenv("DYLD_FALLBACK_LIBRARY_PATH", runtimeDir, 1)

        val simulatorRoot = getenv("SIMULATOR_ROOT")?.toKString()
        if (!simulatorRoot.isNullOrEmpty()) {
            setenv("DYLD_ROOT_PATH", simulatorRoot, 1)
        } else {
            setenv("DYLD_ROOT_PATH", "", 1)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun buildFailureDebug(
        location: RuntimeLocation,
        args: List<String>,
        exitCode: Int,
        preloadFailures: List<String>,
    ): String {
        fun env(name: String): String = getenv(name)?.toKString().orEmpty()

        return buildString {
            append("\n[FFmpegKit debug]\n")
            append("binarySource=")
            append(location.source)
            append("\ncommand=")
            append("ffmpeg")
            if (args.isNotEmpty()) {
                append(' ')
                append(args.joinToString(" "))
            }
            append("\nexitCode=")
            append(exitCode)
            append("\nruntimeDir=")
            append(location.runtimeDir)
            append("\nwrapperPath=")
            append(location.wrapperPath)
            append("\nffmpegPath=")
            append(location.ffmpegPath)
            append("\npreloadFailures=")
            if (preloadFailures.isEmpty()) {
                append("<none>")
            } else {
                append(preloadFailures.joinToString("; "))
            }
            append("\nDYLD_LIBRARY_PATH=")
            append(env("DYLD_LIBRARY_PATH"))
            append("\nDYLD_FALLBACK_LIBRARY_PATH=")
            append(env("DYLD_FALLBACK_LIBRARY_PATH"))
            append("\nDYLD_ROOT_PATH=")
            append(env("DYLD_ROOT_PATH"))
            append("\nSIMULATOR_ROOT=")
            append(env("SIMULATOR_ROOT"))
            append("\nSIMULATOR_UDID=")
            append(env("SIMULATOR_UDID"))
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

    private fun resolveRuntimeLocation(): RuntimeLocation {
        val bundle = NSBundle.mainBundle
        val resourcePath = bundle.resourcePath
            ?: error("NSBundle.resourcePath is null.")
        val frameworksPath = bundle.privateFrameworksPath?.takeIf { it.isNotBlank() }
        val configuredRuntimeDir = runtimeSearchPath?.takeIf { it.isNotBlank() }
        val candidateDirs = buildList {
            configuredRuntimeDir?.let { add(it) }
            add(resourcePath)
            frameworksPath?.let { if (it != resourcePath) add(it) }
        }
        val fileManager = NSFileManager.defaultManager
        val runtimeDir = candidateDirs.firstOrNull { dir ->
            fileManager.fileExistsAtPath("$dir/libffmpegkitcmd.dylib")
        } ?: error(
            "libffmpegkitcmd.dylib not found in app bundle resource path or Frameworks directory: " +
                candidateDirs.joinToString()
        )
        val wrapperPath = "$runtimeDir/libffmpegkitcmd.dylib"
        val ffmpegPath = candidateDirs.firstOrNull { dir ->
            fileManager.fileExistsAtPath("$dir/ffmpeg")
        }?.let { "$it/ffmpeg" }.orEmpty()

        return RuntimeLocation(
            wrapperPath = wrapperPath,
            ffmpegPath = ffmpegPath,
            runtimeDir = runtimeDir,
            source = when (runtimeDir) {
                configuredRuntimeDir -> "configured"
                resourcePath -> "bundle-resource"
                frameworksPath -> "bundle-frameworks"
                else -> "bundle"
            },
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun preloadRuntimeLibraries(runtimeDir: String): List<String> {
        val entries = NSFileManager.defaultManager.contentsOfDirectoryAtPath(runtimeDir, null)
            ?.mapNotNull { it as? String }
            .orEmpty()
        val dylibs = entries
            .filter { it.startsWith("lib") && it.endsWith(".dylib") && it != "libffmpegkitcmd.dylib" }
            .sorted()

        val failures = mutableListOf<String>()
        dylibs.forEach { fileName ->
            val path = "$runtimeDir/$fileName"
            if (loadedLibraryPaths.contains(path)) return@forEach
            val handle = dlopen(path, RTLD_NOW or RTLD_GLOBAL)
            if (handle == null) {
                failures += "$fileName: ${dlerrorMessage()}"
            } else {
                loadedLibraryPaths += path
                loadedHandles += handle
            }
        }
        return failures
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun loadOrResolveEntryPoint(wrapperPath: String): FfmpegExecuteFn {
        if (cachedWrapperPath == wrapperPath && cachedEntryPoint != null) {
            return cachedEntryPoint!!
        }
        val handle = dlopen(wrapperPath, RTLD_NOW or RTLD_LOCAL)
            ?: error("dlopen($wrapperPath) failed: ${dlerrorMessage()}")
        val symbol = dlsym(handle, "ffmpegkit_execute")
            ?: error("dlsym(ffmpegkit_execute) failed: ${dlerrorMessage()}")
        val fn = symbol.reinterpret<CFunction<(Int, CPointer<CPointerVar<ByteVar>>?) -> Int>>()
        cachedWrapperPath = wrapperPath
        cachedEntryPoint = fn
        loadedHandles += handle
        return fn
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readCaptureFile(path: String): String {
        val fd = open(path, O_RDONLY)
        if (fd < 0) return ""

        val output = StringBuilder()
        val buffer = ByteArray(4096)
        try {
            buffer.usePinned { pinned ->
                while (true) {
                    val readSize = read(fd, pinned.addressOf(0), buffer.size.convert())
                    if (readSize <= 0) break
                    output.append(buffer.decodeToString(0, readSize.toInt()))
                }
            }
        } finally {
            close(fd)
        }
        return output.toString()
    }

    private fun createCapturePath(runtimeDir: String, stream: String): String {
        val tempRoot = NSTemporaryDirectory().ifEmpty { runtimeDir }
        val token = "${getpid()}-${Random.nextInt(0, Int.MAX_VALUE).toString(16)}"
        return "$tempRoot/mediamp-ffmpeg-$stream-$token.log"
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun dlerrorMessage(): String = dlerror()?.toKString() ?: "unknown"

    private data class RuntimeLocation(
        val wrapperPath: String,
        val ffmpegPath: String,
        val runtimeDir: String,
        val source: String,
    )

    private data class RunResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    public companion object {
        public fun initialize(runtimeSearchPath: String) {
            this.runtimeSearchPath = runtimeSearchPath.trimEnd('/')
        }

        @OptIn(ExperimentalForeignApi::class)
        private val executionMutex: Mutex = Mutex()
        @OptIn(ExperimentalForeignApi::class)
        private val loadedLibraryPaths: MutableSet<String> = linkedSetOf()
        @OptIn(ExperimentalForeignApi::class)
        private val loadedHandles: MutableList<CPointer<*>> = mutableListOf()
        @OptIn(ExperimentalForeignApi::class)
        private var cachedWrapperPath: String? = null
        @OptIn(ExperimentalForeignApi::class)
        private var cachedEntryPoint: FfmpegExecuteFn? = null
        private var runtimeSearchPath: String? = null
    }
}

@OptIn(ExperimentalForeignApi::class)
private typealias FfmpegExecuteFn = CPointer<CFunction<(Int, CPointer<CPointerVar<ByteVar>>?) -> Int>>
