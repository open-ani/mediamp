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

    /**
     * Version probe command lines whose combined output identifies the toolchain for the
     * build cache (see [nativebuild.ToolchainFingerprintValueSource]). Probes must be
     * runnable directly by the JVM (native paths, no MSYS path forms).
     */
    val toolchainProbes: List<List<String>> = emptyList(),

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

    // ---- dav1d (AV1 software decoding) ----

    /**
     * Build dav1d from the `mediamp-ffmpeg/dav1d` submodule and statically link it into
     * libavcodec (`--enable-libdav1d --enable-decoder=libdav1d`). Required for software
     * AV1 playback: FFmpeg 8 removed the native AV1 software decoder, leaving
     * `libavcodec/av1dec.c` as a hwaccel-only shim that fails with ENOSYS when no AV1
     * hardware decoder exists on the machine.
     */
    val dav1dEnabled: Boolean = false,
    /** Extra `meson setup` arguments for the dav1d build (e.g. macOS version-min flags). */
    val dav1dMesonArgs: List<String> = emptyList(),
    /** MSYS2 packages the dav1d meson build needs (Windows targets). */
    val dav1dMsys2Packages: List<String> = emptyList(),
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
    // Small built-in codec used by the native-runtime smoke tests to generate fixtures
    // without relying on an externally installed libx264-enabled ffmpeg.
    add("--enable-decoder=mpeg4")
    // MPEG-2 video: DVD rips, older TV rips, and Japanese broadcast TS.
    add("--enable-decoder=mpeg2video")
    // VC-1: early Blu-ray (2006-2010) releases.
    add("--enable-decoder=vc1")
    // WMV family. wmv3 (WMV9) rides on the vc1 decoder; wmv1/wmv2 cover older
    // ASF-era files.
    add("--enable-decoder=wmv1")
    add("--enable-decoder=wmv2")
    add("--enable-decoder=wmv3")
    // DivX 3 in early-2000s AVI fansubs; shares msmpeg4dec with wmv1/wmv2.
    add("--enable-decoder=msmpeg4v3")
    // RealMedia (rmvb): the dominant format of 2000s Chinese-community anime rips.
    add("--enable-decoder=rv30")
    add("--enable-decoder=rv40")
    add("--enable-decoder=wrapped_avframe")
    add("--enable-decoder=pcm_s16le")
    add("--enable-encoder=mpeg4")
    add("--enable-encoder=aac")
    add("--enable-encoder=srt")
    add("--enable-decoder=aac")
    add("--enable-decoder=opus")
    add("--enable-decoder=mp3")
    add("--enable-decoder=flac")
    // Dolby Digital (AC-3) and Dolby Digital Plus (E-AC-3), common audio tracks in
    // WEB-DLs and BDRips. eac3 selects the ac3 decoder in configure; both are listed
    // explicitly for clarity.
    add("--enable-decoder=ac3")
    add("--enable-decoder=eac3")
    // DTS incl. DTS-HD MA (the dca decoder handles core + lossless extensions).
    add("--enable-decoder=dca")
    // Dolby TrueHD (BD remuxes; Atmos carriers decode as TrueHD).
    add("--enable-decoder=truehd")
    // Vorbis audio in older Matroska releases; the ogg demuxer is already enabled
    // but had no matching decoder.
    add("--enable-decoder=vorbis")
    // LPCM variants beyond pcm_s16le: Blu-ray LPCM in m2ts and 24-bit PCM in mkv.
    add("--enable-decoder=pcm_bluray")
    add("--enable-decoder=pcm_s24le")
    // Japanese broadcast TS uses LATM/LOAS AAC, which the plain aac decoder does
    // not accept.
    add("--enable-decoder=aac_latm")
    // WMA audio for WMV/ASF files.
    add("--enable-decoder=wmav1")
    add("--enable-decoder=wmav2")
    add("--enable-decoder=wmapro")
    // RealAudio codecs found in rmvb: cook is the common one, sipr appears in
    // some speech-heavy rips, atrac3 in pre-rmvb .rm files.
    add("--enable-decoder=cook")
    add("--enable-decoder=sipr")
    add("--enable-decoder=atrac3")
    // DVD / DVB broadcast audio; shares the mpegaudio core with mp3.
    add("--enable-decoder=mp2")
    // Early-2000s AVI audio tracks and old QuickTime PCM.
    add("--enable-decoder=adpcm_ms")
    add("--enable-decoder=adpcm_ima_wav")
    add("--enable-decoder=pcm_u8")
    add("--enable-decoder=pcm_s16be")
    // Apple Lossless in m4a/mov.
    add("--enable-decoder=alac")
    // Subtitle decoders. The Matroska/mov demuxers still enumerate subtitle streams
    // without these, so track pickers list tracks that then fail to open — mpv logs
    // "Could not find subtitle decoder for format 'subrip'" and silently deselects
    // the track (open-ani/animeko#1128).
    add("--enable-decoder=ass")
    add("--enable-decoder=ssa")
    add("--enable-decoder=srt")
    add("--enable-decoder=subrip")
    add("--enable-decoder=text")
    add("--enable-decoder=webvtt")
    // configure component names come from ff_<name>_decoder symbols: the MP4 timed-text
    // decoder is "movtext" here even though `ffmpeg -decoders` lists it as "mov_text"
    // (unknown --enable-decoder values are silently ignored).
    add("--enable-decoder=movtext")
    add("--enable-decoder=pgssub")
    add("--enable-decoder=dvdsub")
    add("--enable-decoder=sami")
    add("--enable-decoder=microdvd")
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
    // WMV/WMA container.
    add("--enable-demuxer=asf")
    // Legacy fansub releases: XviD/DivX in AVI.
    add("--enable-demuxer=avi")
    // RealMedia container (.rm/.rmvb).
    add("--enable-demuxer=rm")
    // External subtitle files (mpv loads them through libavformat).
    add("--enable-demuxer=ass")
    add("--enable-demuxer=srt")
    add("--enable-demuxer=webvtt")
    // External VobSub (.idx/.sub pairs, AVI-era releases; selects mpegps_demuxer),
    // SAMI (.smi, Korean fansubs) and MicroDVD (text .sub).
    add("--enable-demuxer=vobsub")
    add("--enable-demuxer=sami")
    add("--enable-demuxer=microdvd")
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
    add("--enable-parser=aac_latm")
    add("--enable-parser=ac3")
    add("--enable-parser=dca")
    add("--enable-parser=mlp")
    add("--enable-parser=opus")
    add("--enable-parser=mpegaudio")
    add("--enable-parser=mpegvideo")
    add("--enable-parser=vc1")
    add("--enable-parser=flac")
    add("--enable-parser=vorbis")
    add("--enable-bsf=h264_mp4toannexb")
    add("--enable-bsf=hevc_mp4toannexb")
    add("--enable-bsf=aac_adtstoasc")
    add("--enable-filter=aresample")
    add("--enable-filter=scale")
    add("--enable-filter=concat")
    add("--enable-filter=color")
    add("--enable-filter=sine")
    add("--enable-filter=testsrc2")
    add("--enable-filter=anull")
    add("--enable-filter=null")
    add("--enable-indev=lavfi")
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
    "--enable-hwaccel=mpeg2_d3d11va",
    "--enable-hwaccel=mpeg2_d3d11va2",
    "--enable-hwaccel=vc1_d3d11va",
    "--enable-hwaccel=vc1_d3d11va2",
    "--enable-hwaccel=wmv3_d3d11va",
    "--enable-hwaccel=wmv3_d3d11va2",
    // FFmpeg has no mpeg4 D3D11VA hwaccel; mpeg4 stays software on Windows.
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
    // Apple Silicon media engines have no MPEG-2 hardware decoder; VideoToolbox
    // still accepts the session (software path inside VT) and mpv falls back
    // cleanly, so enabling this is harmless there and a real win on Intel Macs.
    "--enable-hwaccel=mpeg2_videotoolbox",
    "--enable-hwaccel=mpeg4_videotoolbox",
    // FFmpeg has no vc1 VideoToolbox hwaccel; VC-1 stays software on macOS.
)

private val linuxRuntimeSearchPathFlags: List<String> = listOf(
    "--extra-ldflags=-Wl,-rpath,'${'$'}ORIGIN'",
)

// dav1d software AV1 decoding. FFmpeg 8 removed the native AV1 software decoder (the
// remaining `av1` decoder is a hwaccel-only shim), so software AV1 needs the external
// libdav1d decoder, built from the mediamp-ffmpeg/dav1d submodule and linked statically
// into libavcodec. The native `av1` decoder stays enabled: the AV1 hwaccels
// (d3d11va/videotoolbox/nvdec/vaapi) attach to it.
private val dav1dFlags: List<String> = listOf(
    "--enable-libdav1d",
    "--enable-decoder=libdav1d",
)

// ---------------------------------------------------------------------------------------
// Windows
// ---------------------------------------------------------------------------------------

internal fun FfmpegBuildContext.hostWindowsTarget(): FfmpegBuildTarget = when (hostArch) {
    Arch.AARCH64 -> windowsArm64Target()
    else -> windowsX64Target()
}

private fun FfmpegBuildContext.windowsX64Target(): FfmpegBuildTarget {
    val msys2Packages = listOf(
        "make",
        "diffutils",
        "pkg-config",
        "mingw-w64-ucrt-x86_64-ca-certificates",
        "mingw-w64-ucrt-x86_64-gcc",
        "mingw-w64-ucrt-x86_64-nasm",
        "mingw-w64-ucrt-x86_64-openssl",
    )
    return FfmpegBuildTarget(
        name = "WindowsX64",
        configureFlags = listOf(
            "--arch=x86_64",
            "--target-os=mingw32",
            "--cc=${msys2Dir.resolve("ucrt64/bin/gcc.exe").absolutePath.toMsysPath()}",
            "--cxx=${msys2Dir.resolve("ucrt64/bin/g++.exe").absolutePath.toMsysPath()}",
        ) + opensslHttpTlsFlags + windowsD3d11vaFlags + dav1dFlags,
        env = mapOf("MSYSTEM" to "UCRT64"),
        shell = msys2Dir.resolve("usr/bin/bash.exe").absolutePath,
        libExtension = "dll",
        libPrefix = "",
        msys2Packages = msys2Packages,
        toolchainProbes = listOf(
            listOf(msys2Dir.resolve("usr/bin/pacman.exe").absolutePath, "-Q") + msys2Packages,
        ),
        jniWrapperName = "ffmpegkitjni.dll",
        jniWrapperExtraLibs = listOf("-lstdc++"),
        msysSubsystem = "ucrt64",
        collectWindowsRuntime = true,
        dav1dEnabled = true,
        dav1dMsys2Packages = listOf(
            "mingw-w64-ucrt-x86_64-gcc",
            "mingw-w64-ucrt-x86_64-meson",
            "mingw-w64-ucrt-x86_64-ninja",
            "mingw-w64-ucrt-x86_64-nasm",
        ),
    )
}

private fun FfmpegBuildContext.windowsArm64Target(): FfmpegBuildTarget {
    val msys2Packages = listOf(
        "make",
        "diffutils",
        "pkg-config",
        "mingw-w64-clang-aarch64-ca-certificates",
        "mingw-w64-clang-aarch64-clang",
        "mingw-w64-clang-aarch64-nasm",
        "mingw-w64-clang-aarch64-openssl",
        "mingw-w64-clang-aarch64-pkgconf",
    )
    return FfmpegBuildTarget(
        name = "WindowsArm64",
        configureFlags = listOf(
            "--arch=aarch64",
            "--target-os=mingw32",
            "--cc=${msys2Dir.resolve("clangarm64/bin/clang.exe").absolutePath.toMsysPath()}",
            "--cxx=${msys2Dir.resolve("clangarm64/bin/clang++.exe").absolutePath.toMsysPath()}",
        ) + opensslHttpTlsFlags + windowsD3d11vaFlags + dav1dFlags,
        env = mapOf("MSYSTEM" to "CLANGARM64"),
        shell = msys2Dir.resolve("usr/bin/bash.exe").absolutePath,
        libExtension = "dll",
        libPrefix = "",
        msys2Packages = msys2Packages,
        toolchainProbes = listOf(
            listOf(msys2Dir.resolve("usr/bin/pacman.exe").absolutePath, "-Q") + msys2Packages,
        ),
        jniWrapperName = "ffmpegkitjni.dll",
        jniWrapperExtraLibs = listOf("-lstdc++"),
        msysSubsystem = "clangarm64",
        collectWindowsRuntime = true,
        dav1dEnabled = true,
        dav1dMsys2Packages = listOf(
            "mingw-w64-clang-aarch64-clang",
            "mingw-w64-clang-aarch64-meson",
            "mingw-w64-clang-aarch64-ninja",
        ),
    )
}

// ---------------------------------------------------------------------------------------
// Linux
// ---------------------------------------------------------------------------------------

internal fun FfmpegBuildContext.linuxX64Target(): FfmpegBuildTarget = FfmpegBuildTarget(
    name = "LinuxX64",
    configureFlags = listOf(
        "--arch=x86_64",
        "--target-os=linux",
        // mpv's `hwdec=nvdec` uses the ordinary software decoders with FFmpeg's
        // NVDEC hwaccels. CUDA/NVDEC driver libraries are loaded dynamically at
        // runtime; ffnvcodec headers are a build-only dependency.
        "--enable-ffnvcodec",
        "--enable-nvdec",
        "--enable-hwaccel=h264_nvdec",
        "--enable-hwaccel=hevc_nvdec",
        "--enable-hwaccel=vp9_nvdec",
        "--enable-hwaccel=av1_nvdec",
        "--enable-hwaccel=mpeg2_nvdec",
        "--enable-hwaccel=mpeg4_nvdec",
        "--enable-hwaccel=vc1_nvdec",
        "--enable-hwaccel=wmv3_nvdec",
        // Intel and AMD share FFmpeg's VAAPI hwdevice. mediamp deliberately uses
        // mpv's vaapi-copy path with the GLX renderer; direct DMA-BUF/EGL import is
        // a separate capability and is not claimed by this build.
        "--enable-vaapi",
        "--enable-hwaccel=h264_vaapi",
        "--enable-hwaccel=hevc_vaapi",
        "--enable-hwaccel=vp9_vaapi",
        "--enable-hwaccel=av1_vaapi",
        "--enable-hwaccel=mpeg2_vaapi",
        "--enable-hwaccel=mpeg4_vaapi",
        "--enable-hwaccel=vc1_vaapi",
        "--enable-hwaccel=wmv3_vaapi",
    ) + opensslHttpTlsFlags + linuxRuntimeSearchPathFlags + dav1dFlags,
    libExtension = "so",
    toolchainProbes = listOf(
        listOf("cc", "--version"),
        listOf("nasm", "--version"),
        listOf("pkg-config", "--modversion", "openssl"),
    ),
    jniWrapperName = "libffmpegkitjni.so",
    dav1dEnabled = true,
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
    ) + secureTransportHttpTlsFlags + macosVideotoolboxFlags + dav1dFlags,
    libExtension = "dylib",
    toolchainProbes = listOf(
        listOf("clang", "--version"),
    ),
    jniWrapperName = "libffmpegkitjni.dylib",
    jniWrapperLinkFlags = listOf("-dynamiclib", "-Wl,-install_name,@loader_path/libffmpegkitjni.dylib"),
    rewriteAppleInstallNames = true,
    dav1dEnabled = true,
    // Match the FFmpeg deployment target so the linker does not warn about object files
    // built for a newer macOS version than the libraries they end up in.
    dav1dMesonArgs = listOf(
        "-Dc_args=-mmacosx-version-min=12.0",
        "-Dc_link_args=-mmacosx-version-min=12.0",
    ),
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
    toolchainProbes = listOf(
        listOf("xcrun", "--sdk", sdk, "clang", "--version"),
        listOf("xcrun", "--sdk", sdk, "--show-sdk-version"),
    ),
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

    // .cmd wrappers need a shell on Windows; probe failures degrade to a stable marker.
    val clangProbe = if (hostOs == Os.Windows) listOf("cmd", "/c", cc, "--version") else listOf(cc, "--version")

    return FfmpegBuildTarget(
        name = androidTargetName(abi),
        libExtension = "so",
        shell = if (hostOs == Os.Windows) msys2Dir.resolve("usr/bin/bash.exe").absolutePath else "bash",
        env = if (hostOs == Os.Windows) mapOf("MSYSTEM" to "UCRT64") else emptyMap(),
        toolchainProbes = listOf(clangProbe),
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
