/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package ffmpeg

import Arch
import getPropertyOrNull
import Os
import getArch
import getOs
import org.gradle.api.Project
import java.io.File
import java.util.Locale
import java.util.Properties

internal data class FfmpegBuildTarget(
    val name: String,
    val extraFlags: List<String>,
    val env: Map<String, String> = emptyMap(),
    val shell: String = "bash",
    val libExtension: String,
    val libPrefix: String = "lib",
)

internal data class AndroidAbi(
    val abi: String,
    val arch: String,
    val clangTriple: String,
    val apiLevel: Int,
)

internal data class DesktopRuntimeTarget(
    val os: String,
    val arch: String,
    val ffmpegTargetName: String,
)

internal data class AppleRuntimeTarget(
    val artifactIdSuffix: String,
    val publicationSuffix: String,
    val ffmpegTargetName: String,
)

internal class FfmpegBuildContext(
    val project: Project,
) {
    val ffmpegSrcDir: File = project.projectDir.resolve("ffmpeg")

    val commandWrapperSource: File = project.projectDir.resolve("src/appleMain/c/ffmpegkit_wrapper.c")
    val jniWrapperSource: File = project.projectDir.resolve("src/jvmMain/c/ffmpegkit_jni.c")

    val enabledBuildVariantFamilies: Set<String> =
        project.getPropertyOrNull("mediamp.ffmpeg.buildvariant")
            ?.split(",")
            ?.map { it.trim().lowercase(Locale.getDefault()) }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?.also { selected ->
                val unknown = selected - ALL_BUILD_VARIANT_FAMILIES
                require(unknown.isEmpty()) {
                    "Unknown values in mediamp.ffmpeg.buildvariant: ${unknown.joinToString()}. " +
                            "Supported values: ${ALL_BUILD_VARIANT_FAMILIES.joinToString()}."
                }
            }
            ?: ALL_BUILD_VARIANT_FAMILIES

    val hostOs: Os = getOs()
    val hostArch: Arch = getArch()

    val makeJobs: Int = Runtime.getRuntime().availableProcessors()

    val hostMacArchFlag: String?
        get() = when (hostArch) {
            Arch.AARCH64 -> "arm64"
            Arch.X86_64 -> "x86_64"
            Arch.UNKNOWN -> null
        }

    val appleHostToolFlags: List<String>
        get() {
            val arch = hostMacArchFlag ?: error("Unknown macOS host architecture for Apple FFmpeg build.")
            val flags = "-arch $arch -mmacosx-version-min=12.0"
            return listOf(
                "--host-cc=xcrun --sdk macosx clang",
                "--host-cflags=$flags",
                "--host-ld=xcrun --sdk macosx clang",
                "--host-ldflags=$flags",
            )
        }

    val commonConfigureFlags: List<String> = buildList {
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
        add("--enable-protocol=file")
        add("--enable-protocol=pipe")
        add("--enable-protocol=concat")
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
        add("--disable-network")
        add("--disable-autodetect")
    }

    val ffmpegLibNames: List<String> = listOf(
        "avcodec",
        "avdevice",
        "avfilter",
        "avformat",
        "avutil",
        "swresample",
        "swscale",
    )

    private val allAndroidAbis: List<AndroidAbi> = listOf(
        AndroidAbi("armeabi-v7a", "arm", "armv7a-linux-androideabi", 21),
        AndroidAbi("arm64-v8a", "aarch64", "aarch64-linux-android", 21),
        AndroidAbi("x86", "x86", "i686-linux-android", 21),
        AndroidAbi("x86_64", "x86_64", "x86_64-linux-android", 21),
    )

    val androidAbis: List<AndroidAbi> =
        project.getPropertyOrNull("mediamp.ffmpeg.androidabis")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.let { selected ->
                val byAbi = allAndroidAbis.associateBy(AndroidAbi::abi)
                val unknown = selected.filterNot(byAbi::containsKey)
                require(unknown.isEmpty()) {
                    "Unknown values in mediamp.ffmpeg.androidabis: ${unknown.joinToString()}. " +
                        "Supported values: ${allAndroidAbis.joinToString { it.abi }}."
                }
                selected.map { byAbi.getValue(it) }
            }
            ?: allAndroidAbis

    val desktopRuntimeTargets: List<DesktopRuntimeTarget> = listOf(
        DesktopRuntimeTarget("windows", "x64", "WindowsX64"),
        DesktopRuntimeTarget("linux", "x64", "LinuxX64"),
        DesktopRuntimeTarget("macos", "arm64", "MacosArm64"),
        DesktopRuntimeTarget("macos", "x64", "MacosX64"),
    )

    val appleRuntimeTargets: List<AppleRuntimeTarget> = listOf(
        AppleRuntimeTarget("ios-arm64", "IosArm64", "IosArm64"),
        AppleRuntimeTarget("ios-simulator-arm64", "IosSimulatorArm64", "IosSimulatorArm64"),
    )

    val linuxX64Target = FfmpegBuildTarget(
        name = "LinuxX64",
        extraFlags = listOf("--arch=x86_64", "--target-os=linux"),
        libExtension = "so",
    )

    val macosArm64Target = FfmpegBuildTarget(
        name = "MacosArm64",
        extraFlags = listOf(
            "--arch=arm64",
            "--target-os=darwin",
            "--cc=clang",
            "--cxx=clang++",
            "--extra-cflags=-arch arm64 -mmacosx-version-min=12.0",
            "--extra-ldflags=-arch arm64 -mmacosx-version-min=12.0",
        ),
        libExtension = "dylib",
    )

    val macosX64Target = FfmpegBuildTarget(
        name = "MacosX64",
        extraFlags = listOf(
            "--arch=x86_64",
            "--target-os=darwin",
            "--cc=clang",
            "--cxx=clang++",
            "--extra-cflags=-arch x86_64 -mmacosx-version-min=12.0",
            "--extra-ldflags=-arch x86_64 -mmacosx-version-min=12.0",
        ),
        libExtension = "dylib",
    )

    val iosArm64Target = FfmpegBuildTarget(
        name = "IosArm64",
        extraFlags = listOf(
            "--arch=arm64",
            "--target-os=darwin",
            "--enable-cross-compile",
            "--cc=xcrun --sdk iphoneos clang",
            "--cxx=xcrun --sdk iphoneos clang++",
            "--extra-cflags=-arch arm64 -miphoneos-version-min=16.0 -fembed-bitcode",
            "--extra-ldflags=-arch arm64 -miphoneos-version-min=16.0",
        ) + appleHostToolFlags,
        shell = "bash",
        libExtension = "dylib",
    )

    val iosSimulatorArm64Target = FfmpegBuildTarget(
        name = "IosSimulatorArm64",
        extraFlags = listOf(
            "--arch=arm64",
            "--target-os=darwin",
            "--enable-cross-compile",
            "--cc=xcrun --sdk iphonesimulator clang",
            "--cxx=xcrun --sdk iphonesimulator clang++",
            "--extra-cflags=-arch arm64 -miphonesimulator-version-min=16.0 -fembed-bitcode",
            "--extra-ldflags=-arch arm64 -miphonesimulator-version-min=16.0",
        ) + appleHostToolFlags,
        shell = "bash",
        libExtension = "dylib",
    )

    fun isBuildVariantEnabled(family: String): Boolean =
        family.lowercase(Locale.getDefault()) in enabledBuildVariantFamilies

    fun resolveNdkDir(): File {
        val explicit = project.getPropertyOrNull("ndk.dir")
            ?: project.getPropertyOrNull("ANDROID_NDK_HOME")
            ?: System.getenv("ANDROID_NDK_HOME")
        if (explicit != null) {
            return project.file(explicit).also {
                require(it.isDirectory) { "Android NDK not found at '$explicit'." }
            }
        }

        val sdkDir = project.getPropertyOrNull("sdk.dir")
            ?: project.getPropertyOrNull("ANDROID_HOME")
            ?: project.getPropertyOrNull("ANDROID_SDK_ROOT")
            ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: error("Android SDK/NDK not found. Set ndk.dir or ANDROID_NDK_HOME.")
        val ndkRoot = project.file(sdkDir).resolve("ndk")
        require(ndkRoot.isDirectory) {
            "Android NDK directory not found under '$sdkDir/ndk'. Set ndk.dir or ANDROID_NDK_HOME."
        }
        val versions = ndkRoot.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }.orEmpty()
        return versions.firstOrNull()
            ?: error("No Android NDK versions found under '$ndkRoot'.")
    }

    fun androidTarget(abi: AndroidAbi): FfmpegBuildTarget {
        val ndkDir = resolveNdkDir()
        val hostTag = when (hostOs) {
            Os.Windows -> "windows-x86_64"
            Os.MacOS -> "darwin-x86_64"
            Os.Linux -> "linux-x86_64"
            Os.Unknown -> error("Unsupported host OS for Android builds")
        }

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
            name = "Android${abi.abi.replace("-", "")}",
            libExtension = "so",
            shell = if (hostOs == Os.Windows) msys2Dir.resolve("usr/bin/bash.exe").absolutePath else "bash",
            env = if (hostOs == Os.Windows) mapOf("MSYSTEM" to "UCRT64") else emptyMap(),
            extraFlags = listOf(
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
            ) + abiSpecificFlags,
        )
    }

    fun resolveMsys2Dir(): File {
        val path = project.getPropertyOrNull("msys2.dir") ?: "C:\\msys64"
        val dir = project.file(path)
        require(dir.isDirectory) {
            "MSYS2 directory not found at '$path'. Set Gradle property msys2.dir to your MSYS2 installation root."
        }
        return dir
    }

    val msys2Dir: File
        get() = resolveMsys2Dir()

    companion object {
        private val ALL_BUILD_VARIANT_FAMILIES = setOf("windows", "linux", "macos", "ios", "android")
    }
}

internal fun String.toMsysPath(): String {
    val normalized = replace("\\", "/")
    return if (normalized.length >= 2 && normalized[1] == ':') {
        "/${normalized[0].lowercaseChar()}${normalized.substring(2)}"
    } else {
        normalized
    }
}

private val Project.localPropertiesFile: File get() = rootProject.file("local.properties")

private fun Project.getLocalProperty(key: String): String? {
    return if (localPropertiesFile.exists()) {
        val properties = Properties()
        localPropertiesFile.inputStream().buffered().use(properties::load)
        properties.getProperty(key)
    } else {
        null
    }
}
