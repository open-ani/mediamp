/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package ffmpeg

import Arch
import Os
import nativebuild.AndroidAbi
import nativebuild.androidNdkHostTag
import nativebuild.androidTargetName
import nativebuild.resolveNdkDir
import nativebuild.toMsysPath

/**
 * Complete build description of one FFmpeg target platform.
 *
 * Everything that varies per platform lives on this class and is produced by the factory
 * functions below — configure flags, toolchain/shell, and the assemble-stage behavior
 * (JNI wrapper, runtime DLL collection, install-name rewriting). Task registration and
 * the task implementations are platform-agnostic consumers of this data.
 */
internal data class FfmpegBuildTarget(
    val name: String,
    /** Appended to [commonConfigureFlags]; the full per-target `./configure` argument list. */
    val configureFlags: List<String>,
    val env: Map<String, String> = emptyMap(),
    val shell: String = "bash",
    val libExtension: String,
    val libPrefix: String = "lib",
    val msys2Packages: List<String> = emptyList(),

    // ---- assemble-stage behavior ----

    /** JVM JNI wrapper library file name, or null when the target has no JVM runtime (iOS). */
    val jniWrapperName: String? = null,
    /** Linker mode flags for the JNI wrapper, e.g. `-shared` or `-dynamiclib` + install_name. */
    val jniWrapperLinkFlags: List<String> = listOf("-shared"),
    /** Extra libraries appended to the JNI wrapper link line. */
    val jniWrapperExtraLibs: List<String> = emptyList(),
    /** Whether the JNI wrapper compiles against the host JDK headers (false on Android). */
    val jniWrapperUseJdkIncludes: Boolean = true,
    /** MSYS2 subsystem used when building on a Windows host ("ucrt64" or "clangarm64"). */
    val msysSubsystem: String = "ucrt64",
    /** Bundle the MSYS2 TLS CA store and recursively collect dependency DLLs (Windows targets). */
    val collectWindowsRuntime: Boolean = false,
    /** Rewrite Mach-O install names to @loader_path after assembly (Apple targets). */
    val rewriteAppleInstallNames: Boolean = false,
    /** Copy the ffmpeg CLI binary into the runtime output when the build produced one. */
    val bundleFfmpegExecutable: Boolean = true,
)

// ---------------------------------------------------------------------------------------
// Shared configure flag groups
// ---------------------------------------------------------------------------------------

/**
 * The trimmed feature set every platform starts from: shared libs, the decoders and
 * container formats mediamp actually plays, and nothing else (`--disable-everything`).
 */
internal val commonConfigureFlags: List<String> = buildList {
    add("--disable-static")
    add("--enable-shared")
    add("--enable-ffmpeg")
    add("--disable-ffplay")
    add("--disable-ffprobe")
    add("--disable-doc")
    add("--disable-everything")
    add("--enable-decoder=h264")
    add("--enable-decoder=hevc")
    add("--enable-decoder=av1")
    add("--enable-decoder=vp9")
    add("--enable-decoder=aac")
    add("--enable-decoder=opus")
    add("--enable-decoder=mp3")
    add("--enable-decoder=flac")
    add("--enable-muxer=mp4")
    add("--enable-muxer=matroska")
    add("--enable-muxer=mpegts")
    add("--enable-demuxer=mpegts")
    add("--enable-demuxer=mov")
    add("--enable-demuxer=matroska")
    add("--enable-demuxer=flac")
    add("--enable-demuxer=mp3")
    add("--enable-demuxer=ogg")
    add("--enable-demuxer=aac")
    add("--enable-demuxer=concat")
    // Local encrypted HLS still needs the HLS demuxer so FFmpeg can interpret
    // EXT-X-KEY metadata instead of treating segments as plain concatenated TS.
    add("--enable-demuxer=hls")
    add("--enable-protocol=file")
    add("--enable-protocol=pipe")
    add("--enable-protocol=concat")
    add("--enable-protocol=crypto")
    add("--enable-parser=h264")
    add("--enable-parser=hevc")
    add("--enable-parser=av1")
    add("--enable-parser=vp9")
    add("--enable-parser=aac")
    add("--enable-parser=opus")
    add("--enable-parser=mpegaudio")
    add("--enable-parser=flac")
    add("--enable-bsf=h264_mp4toannexb")
    add("--enable-bsf=hevc_mp4toannexb")
    add("--enable-bsf=aac_adtstoasc")
    add("--enable-filter=aresample")
    add("--enable-filter=scale")
    add("--enable-filter=concat")
    add("--enable-filter=anull")
    add("--enable-filter=null")
    add("--enable-swresample")
    add("--enable-swscale")
    add("--disable-debug")
    add("--disable-autodetect")
}

private val httpTlsProtocolFlags: List<String> = listOf(
    "--enable-protocol=udp",
    "--enable-protocol=tcp",
    "--enable-protocol=tls",
    "--enable-protocol=http",
    "--enable-protocol=https",
)

private val opensslHttpTlsFlags: List<String> = listOf(
    "--enable-openssl",
) + httpTlsProtocolFlags

private val secureTransportHttpTlsFlags: List<String> = listOf(
    "--enable-securetransport",
) + httpTlsProtocolFlags

// D3D11VA hardware decoding: hwcontext_d3d11va for mpv's hwdec=d3d11va
// (--disable-everything/--disable-autodetect strip these otherwise; without
// them mpv falls back to software decoding on Windows).
private val windowsD3d11vaFlags: List<String> = listOf(
    "--enable-d3d11va",
    "--enable-hwaccel=h264_d3d11va",
    "--enable-hwaccel=h264_d3d11va2",
    "--enable-hwaccel=hevc_d3d11va",
    "--enable-hwaccel=hevc_d3d11va2",
    "--enable-hwaccel=vp9_d3d11va",
    "--enable-hwaccel=vp9_d3d11va2",
    "--enable-hwaccel=av1_d3d11va",
    "--enable-hwaccel=av1_d3d11va2",
)

// VideoToolbox hardware decoding: hwcontext_videotoolbox for mpv's hwdec=videotoolbox
// (--disable-everything/--disable-autodetect strip these otherwise; without them
// av_hwdevice_ctx_create(AV_HWDEVICE_TYPE_VIDEOTOOLBOX) returns AVERROR(ENOMEM) —
// libavutil's hw_table has no videotoolbox entry — and mpv falls back to software
// decoding on macOS. mpv itself is built with -Dvideotoolbox-gl=enabled, so the GL
// interop loads fine; only the ffmpeg hwdevice was missing.
private val macosVideotoolboxFlags: List<String> = listOf(
    "--enable-videotoolbox",
    "--enable-hwaccel=h264_videotoolbox",
    "--enable-hwaccel=hevc_videotoolbox",
    "--enable-hwaccel=vp9_videotoolbox",
    "--enable-hwaccel=av1_videotoolbox",
)

private val linuxRuntimeSearchPathFlags: List<String> = listOf(
    "--extra-ldflags=-Wl,-rpath,'${'$'}ORIGIN'",
)

// ---------------------------------------------------------------------------------------
// Windows
// ---------------------------------------------------------------------------------------

internal fun FfmpegBuildContext.hostWindowsTarget(): FfmpegBuildTarget = when (hostArch) {
    Arch.AARCH64 -> windowsArm64Target()
    else -> windowsX64Target()
}

private fun FfmpegBuildContext.windowsX64Target(): FfmpegBuildTarget = FfmpegBuildTarget(
    name = "WindowsX64",
    configureFlags = listOf(
        "--arch=x86_64",
        "--target-os=mingw32",
        "--cc=${msys2Dir.resolve("ucrt64/bin/gcc.exe").absolutePath.toMsysPath()}",
        "--cxx=${msys2Dir.resolve("ucrt64/bin/g++.exe").absolutePath.toMsysPath()}",
    ) + opensslHttpTlsFlags + windowsD3d11vaFlags,
    env = mapOf("MSYSTEM" to "UCRT64"),
    shell = msys2Dir.resolve("usr/bin/bash.exe").absolutePath,
    libExtension = "dll",
    libPrefix = "",
    msys2Packages = listOf(
        "make",
        "diffutils",
        "pkg-config",
        "mingw-w64-ucrt-x86_64-ca-certificates",
        "mingw-w64-ucrt-x86_64-gcc",
        "mingw-w64-ucrt-x86_64-nasm",
        "mingw-w64-ucrt-x86_64-openssl",
    ),
    jniWrapperName = "ffmpegkitjni.dll",
    jniWrapperExtraLibs = listOf("-lstdc++"),
    msysSubsystem = "ucrt64",
    collectWindowsRuntime = true,
)

private fun FfmpegBuildContext.windowsArm64Target(): FfmpegBuildTarget = FfmpegBuildTarget(
    name = "WindowsArm64",
    configureFlags = listOf(
        "--arch=aarch64",
        "--target-os=mingw32",
        "--cc=${msys2Dir.resolve("clangarm64/bin/clang.exe").absolutePath.toMsysPath()}",
        "--cxx=${msys2Dir.resolve("clangarm64/bin/clang++.exe").absolutePath.toMsysPath()}",
    ) + opensslHttpTlsFlags,
    env = mapOf("MSYSTEM" to "CLANGARM64"),
    shell = msys2Dir.resolve("usr/bin/bash.exe").absolutePath,
    libExtension = "dll",
    libPrefix = "",
    msys2Packages = listOf(
        "make",
        "diffutils",
        "pkg-config",
        "mingw-w64-clang-aarch64-ca-certificates",
        "mingw-w64-clang-aarch64-clang",
        "mingw-w64-clang-aarch64-nasm",
        "mingw-w64-clang-aarch64-openssl",
        "mingw-w64-clang-aarch64-pkgconf",
    ),
    jniWrapperName = "ffmpegkitjni.dll",
    jniWrapperExtraLibs = listOf("-lstdc++"),
    msysSubsystem = "clangarm64",
    collectWindowsRuntime = true,
)

// ---------------------------------------------------------------------------------------
// Linux
// ---------------------------------------------------------------------------------------

internal fun FfmpegBuildContext.linuxX64Target(): FfmpegBuildTarget = FfmpegBuildTarget(
    name = "LinuxX64",
    configureFlags = listOf(
        "--arch=x86_64",
        "--target-os=linux",
    ) + opensslHttpTlsFlags + linuxRuntimeSearchPathFlags,
    libExtension = "so",
    jniWrapperName = "libffmpegkitjni.so",
)

// ---------------------------------------------------------------------------------------
// macOS
// ---------------------------------------------------------------------------------------

internal fun FfmpegBuildContext.hostMacosTarget(): FfmpegBuildTarget = when (hostArch) {
    Arch.AARCH64 -> macosTarget(name = "MacosArm64", arch = "arm64")
    Arch.X86_64 -> macosTarget(name = "MacosX64", arch = "x86_64")
    else -> error("Failed to configure FFmpeg tasks, unknown macOS host.")
}

private fun macosTarget(name: String, arch: String): FfmpegBuildTarget = FfmpegBuildTarget(
    name = name,
    configureFlags = listOf(
        "--arch=$arch",
        "--target-os=darwin",
        "--cc=clang",
        "--cxx=clang++",
        "--extra-cflags=-arch $arch -mmacosx-version-min=12.0",
        "--extra-ldflags=-arch $arch -mmacosx-version-min=12.0",
    ) + secureTransportHttpTlsFlags + macosVideotoolboxFlags,
    libExtension = "dylib",
    jniWrapperName = "libffmpegkitjni.dylib",
    jniWrapperLinkFlags = listOf("-dynamiclib", "-Wl,-install_name,@loader_path/libffmpegkitjni.dylib"),
    rewriteAppleInstallNames = true,
)

// ---------------------------------------------------------------------------------------
// iOS (static libs packaged as an Apple framework; no JVM runtime)
// ---------------------------------------------------------------------------------------

internal fun FfmpegBuildContext.iosArm64Target(): FfmpegBuildTarget =
    iosTarget(name = "IosArm64", sdk = "iphoneos", versionMinFlag = "-miphoneos-version-min=16.0")

internal fun FfmpegBuildContext.iosSimulatorArm64Target(): FfmpegBuildTarget =
    iosTarget(name = "IosSimulatorArm64", sdk = "iphonesimulator", versionMinFlag = "-miphonesimulator-version-min=16.0")

private fun FfmpegBuildContext.iosTarget(
    name: String,
    sdk: String,
    versionMinFlag: String,
): FfmpegBuildTarget = FfmpegBuildTarget(
    name = name,
    configureFlags = listOf(
        "--disable-shared",
        "--enable-static",
        "--arch=arm64",
        "--target-os=darwin",
        "--enable-cross-compile",
        "--cc=xcrun --sdk $sdk clang",
        "--cxx=xcrun --sdk $sdk clang++",
        "--extra-cflags=-arch arm64 $versionMinFlag",
        "--extra-ldflags=-arch arm64 $versionMinFlag",
    ) + appleHostToolFlags + secureTransportHttpTlsFlags,
    shell = "bash",
    libExtension = "a",
    jniWrapperName = null,
    rewriteAppleInstallNames = true,
    bundleFfmpegExecutable = false,
)

private val FfmpegBuildContext.appleHostToolFlags: List<String>
    get() {
        val arch = when (hostArch) {
            Arch.AARCH64 -> "arm64"
            Arch.X86_64 -> "x86_64"
            Arch.UNKNOWN -> error("Unknown macOS host architecture for Apple FFmpeg build.")
        }
        val flags = "-arch $arch -mmacosx-version-min=12.0"
        return listOf(
            "--host-cc=xcrun --sdk macosx clang",
            "--host-cflags=$flags",
            "--host-ld=xcrun --sdk macosx clang",
            "--host-ldflags=$flags",
        )
    }

// ---------------------------------------------------------------------------------------
// Android
// ---------------------------------------------------------------------------------------

internal fun FfmpegBuildContext.androidTarget(abi: AndroidAbi): FfmpegBuildTarget {
    val ndkDir = project.resolveNdkDir()
    val hostTag = androidNdkHostTag(hostOs)

    val binDir = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/bin")
    require(binDir.isDirectory) {
        "NDK LLVM toolchain not found at '$binDir'."
    }

    val clangSuffix = if (hostOs == Os.Windows) ".cmd" else ""
    val cc = binDir.resolve("${abi.clangTriple}${abi.apiLevel}-clang$clangSuffix").absolutePath
    val cxx = binDir.resolve("${abi.clangTriple}${abi.apiLevel}-clang++$clangSuffix").absolutePath
    val exeSuffix = if (hostOs == Os.Windows) ".exe" else ""
    val ar = binDir.resolve("llvm-ar$exeSuffix").absolutePath
    val nm = binDir.resolve("llvm-nm$exeSuffix").absolutePath
    val strip = binDir.resolve("llvm-strip$exeSuffix").absolutePath
    val ranlib = binDir.resolve("llvm-ranlib$exeSuffix").absolutePath
    val sysroot = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/sysroot")

    fun String.msysIfWin(): String = if (hostOs == Os.Windows) toMsysPath() else this

    val abiSpecificFlags = when (abi.abi) {
        "x86", "x86_64" -> listOf("--disable-asm", "--disable-x86asm")
        else -> emptyList()
    }

    return FfmpegBuildTarget(
        name = androidTargetName(abi),
        libExtension = "so",
        shell = if (hostOs == Os.Windows) msys2Dir.resolve("usr/bin/bash.exe").absolutePath else "bash",
        env = if (hostOs == Os.Windows) mapOf("MSYSTEM" to "UCRT64") else emptyMap(),
        configureFlags = listOf(
            // Android keeps network disabled until a cross-compiled TLS backend
            // is wired in for this target family.
            "--disable-network",
            "--arch=${abi.arch}",
            "--target-os=android",
            "--enable-cross-compile",
            "--cc=${cc.msysIfWin()}",
            "--cxx=${cxx.msysIfWin()}",
            "--ar=${ar.msysIfWin()}",
            "--nm=${nm.msysIfWin()}",
            "--strip=${strip.msysIfWin()}",
            "--ranlib=${ranlib.msysIfWin()}",
            "--sysroot=${sysroot.absolutePath.msysIfWin()}",
            "--extra-cflags=-fPIC",
            "--enable-pic",
            "--enable-jni",
        ) + abiSpecificFlags,
        jniWrapperName = "libffmpegkitjni.so",
        jniWrapperUseJdkIncludes = false,
    )
}
