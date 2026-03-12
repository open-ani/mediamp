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
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.posix.RTLD_GLOBAL
import platform.posix.RTLD_LOCAL
import platform.posix.RTLD_NOW
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym
import platform.posix.getenv
import platform.posix.setenv

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
                    val runResult = runInProcess(runtime, args, preloadFailures)
                    val debugSuffix = if (runResult.exitCode == 0) {
                        ""
                    } else {
                        buildFailureDebug(runtime, args, runResult.exitCode, preloadFailures)
                    }
                    if (debugSuffix.isNotEmpty()) {
                        configuredLogHandler?.onLog(FFmpegLogMessage(16, debugSuffix))
                    }
                    FFmpegResult(exitCode = runResult.exitCode)
                } catch (t: Throwable) {
                    configuredLogHandler?.onLog(FFmpegLogMessage(16, buildExceptionDebug(t, args)))
                    FFmpegResult(exitCode = 1)
                }
            }
        }

    @OptIn(ExperimentalForeignApi::class)
    private fun runInProcess(location: RuntimeLocation, args: List<String>, preloadFailures: List<String>): RunResult = memScoped {
        val commandArgs = buildList {
            add("ffmpeg")
            addAll(args)
        }

        var exitCode = -1
        val collector = FFmpegLogLineCollector { message ->
            configuredLogHandler?.onLog(message)
        }
        val nativeFns = loadOrResolveNativeFunctions(location.wrapperPath)
        withActiveLogCollector(collector) {
            nativeFns.setLogCallback(nativeLogCallback)
            preloadFailures.forEach { configuredLogHandler?.onLog(FFmpegLogMessage(16, "[preload] $it")) }
            val executeFn = nativeFns.execute
            val argv = allocArray<CPointerVar<ByteVar>>(commandArgs.size + 1)
            commandArgs.forEachIndexed { index, value ->
                argv[index] = value.cstr.ptr
            }
            argv[commandArgs.size] = null
            exitCode = executeFn(commandArgs.size, argv)
            nativeFns.setLogCallback(null)
        }

        return@memScoped RunResult(exitCode)
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
    private fun loadOrResolveNativeFunctions(wrapperPath: String): NativeFunctions {
        if (cachedWrapperPath == wrapperPath && cachedNativeFunctions != null) {
            return cachedNativeFunctions!!
        }
        val handle = dlopen(wrapperPath, RTLD_NOW or RTLD_LOCAL)
            ?: error("dlopen($wrapperPath) failed: ${dlerrorMessage()}")
        val executeSymbol = dlsym(handle, "ffmpegkit_execute")
            ?: error("dlsym(ffmpegkit_execute) failed: ${dlerrorMessage()}")
        val setLogCallbackSymbol = dlsym(handle, "ffmpegkit_set_log_callback")
            ?: error("dlsym(ffmpegkit_set_log_callback) failed: ${dlerrorMessage()}")
        val nativeFunctions = NativeFunctions(
            execute = executeSymbol.reinterpret(),
            setLogCallback = setLogCallbackSymbol.reinterpret(),
        )
        cachedWrapperPath = wrapperPath
        cachedNativeFunctions = nativeFunctions
        loadedHandles += handle
        return nativeFunctions
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
    )

    public actual companion object {
        public fun initialize(runtimeSearchPath: String) {
            this.runtimeSearchPath = runtimeSearchPath.trimEnd('/')
        }

        public actual fun setLogHandler(handler: FFmpegLogHandler?) {
            configuredLogHandler = handler
        }

        private fun withActiveLogCollector(collector: FFmpegLogLineCollector, block: () -> Unit) {
            activeLogCollector = collector
            try {
                block()
            } finally {
                collector.flush()
                activeLogCollector = null
            }
        }

        @OptIn(ExperimentalForeignApi::class)
        private val nativeLogCallback = staticCFunction<Int, CPointer<ByteVar>?, Unit> { level, message ->
            val text = message?.toKString().orEmpty()
            activeLogCollector?.append(level, text)
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
        private var cachedNativeFunctions: NativeFunctions? = null
        private var configuredLogHandler: FFmpegLogHandler? = null
        private var activeLogCollector: FFmpegLogLineCollector? = null
        private var runtimeSearchPath: String? = null
    }
}

@OptIn(ExperimentalForeignApi::class)
private typealias FfmpegExecuteFn = CPointer<CFunction<(Int, CPointer<CPointerVar<ByteVar>>?) -> Int>>
@OptIn(ExperimentalForeignApi::class)
private typealias FfmpegSetLogCallbackFn = CPointer<CFunction<(CPointer<CFunction<(Int, CPointer<ByteVar>?) -> Unit>>?) -> Unit>>

@OptIn(ExperimentalForeignApi::class)
private data class NativeFunctions(
    val execute: FfmpegExecuteFn,
    val setLogCallback: FfmpegSetLogCallbackFn,
)
