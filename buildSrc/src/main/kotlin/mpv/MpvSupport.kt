package mpv

import Arch
import Os
import getArch
import getOs
import getPropertyOrNull
import nativebuild.AndroidAbi
import nativebuild.DEFAULT_ANDROID_ABIS
import nativebuild.DEFAULT_DESKTOP_RUNTIME_TARGETS
import nativebuild.DesktopRuntimeTarget
import nativebuild.FFMPEG_NATIVE_BUILD_PROPERTIES
import nativebuild.MPV_NATIVE_BUILD_PROPERTIES
import nativebuild.NativeBuildProperties
import nativebuild.androidNdkHostTag
import nativebuild.includesBuildVariant
import nativebuild.resolveAndroidAbis
import nativebuild.resolveEnabledBuildVariantFamilies
import nativebuild.resolveNdkDir
import org.gradle.api.Project
import java.io.File

internal data class AndroidToolchain(
    val cc: File,
    val cxx: File,
    val ccArgs: List<String>,
    val cxxArgs: List<String>,
    val ar: File,
    val nm: File,
    val strip: File,
    val ranlib: File,
    val sysroot: File,
    val libcxxShared: File,
    val sysrootTriple: String,
    val cpuFamily: String,
    val cpu: String,
)

/**
 * Host/project environment for the mpv build. Per-platform build configuration lives in
 * [MpvTargets.kt](MpvBuildTarget); this class only carries what the target factories and
 * task registration need to look things up.
 */
internal class MpvBuildContext(
    val project: Project,
    val mpvPatch: File,
) {
    val buildProperties: NativeBuildProperties = MPV_NATIVE_BUILD_PROPERTIES
    val ffmpegBuildProperties: NativeBuildProperties = FFMPEG_NATIVE_BUILD_PROPERTIES

    val mpvProjectDir: File = project.projectDir
    val mpvSrcDir: File = project.projectDir.resolve("mpv")
    val ffmpegProject: Project = project.rootProject.project(":mediamp-ffmpeg")

    val hostOs: Os = getOs()
    val hostArch: Arch = getArch()

    val enabledBuildVariantFamilies: Set<String> =
        project.resolveEnabledBuildVariantFamilies(buildProperties, ALL_BUILD_VARIANT_FAMILIES)

    val mesonBuildType: String =
        project.getPropertyOrNull("mediamp.mpv.buildtype")?.trim()?.takeIf { it.isNotEmpty() } ?: "release"

    val androidAbis: List<AndroidAbi> =
        project.resolveAndroidAbis(buildProperties, availableAbis = DEFAULT_ANDROID_ABIS)

    val desktopRuntimeTargets: List<DesktopRuntimeTarget> = DEFAULT_DESKTOP_RUNTIME_TARGETS

    fun isBuildVariantEnabled(family: String): Boolean =
        enabledBuildVariantFamilies.includesBuildVariant(family)

    fun hostDesktopTargetName(): String? = when (hostOs) {
        Os.Windows -> when (hostArch) {
            Arch.AARCH64 -> "WindowsArm64"
            Arch.X86_64 -> "WindowsX64"
            Arch.UNKNOWN -> null
        }
        Os.Linux -> "LinuxX64"
        Os.MacOS -> when (hostArch) {
            Arch.AARCH64 -> "MacosArm64"
            Arch.X86_64 -> "MacosX64"
            Arch.UNKNOWN -> null
        }
        Os.Unknown -> null
    }

    fun ffmpegInstallDir(targetName: String): File =
        ffmpegProject.projectDir.resolve("build/ffmpeg/$targetName/install")

    fun ffmpegAssembleTaskName(targetName: String): String = "ffmpegAssemble$targetName"

    fun androidToolchain(abi: AndroidAbi): AndroidToolchain {
        val ndkDir = project.resolveNdkDir()
        val hostTag = androidNdkHostTag(hostOs)

        val binDir = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/bin")
        require(binDir.isDirectory) {
            "NDK LLVM toolchain not found at '$binDir'."
        }

        val exeSuffix = if (hostOs == Os.Windows) ".exe" else ""
        val cc = if (hostOs == Os.Windows) {
            binDir.resolve("clang$exeSuffix")
        } else {
            binDir.resolve("${abi.clangTriple}${abi.apiLevel}-clang")
        }
        val cxx = if (hostOs == Os.Windows) {
            binDir.resolve("clang++$exeSuffix")
        } else {
            binDir.resolve("${abi.clangTriple}${abi.apiLevel}-clang++")
        }
        val ar = binDir.resolve("llvm-ar$exeSuffix")
        val nm = binDir.resolve("llvm-nm$exeSuffix")
        val strip = binDir.resolve("llvm-strip$exeSuffix")
        val ranlib = binDir.resolve("llvm-ranlib$exeSuffix")
        val sysroot = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/sysroot")
        val targetTripleWithApi = "${abi.clangTriple}${abi.apiLevel}"

        val sysrootTriple = when (abi.abi) {
            "armeabi-v7a" -> "arm-linux-androideabi"
            "arm64-v8a" -> "aarch64-linux-android"
            "x86" -> "i686-linux-android"
            "x86_64" -> "x86_64-linux-android"
            else -> error("Unsupported Android ABI: ${abi.abi}")
        }

        val cpuFamily = when (abi.abi) {
            "armeabi-v7a" -> "arm"
            "arm64-v8a" -> "aarch64"
            "x86" -> "x86"
            "x86_64" -> "x86_64"
            else -> error("Unsupported Android ABI: ${abi.abi}")
        }

        val cpu = when (abi.abi) {
            "armeabi-v7a" -> "armv7"
            "arm64-v8a" -> "armv8-a"
            "x86" -> "i686"
            "x86_64" -> "x86_64"
            else -> error("Unsupported Android ABI: ${abi.abi}")
        }

        val libcxxCandidates = listOf(
            sysroot.resolve("usr/lib/$sysrootTriple/libc++_shared.so"),
            sysroot.resolve("usr/lib/$sysrootTriple/${abi.apiLevel}/libc++_shared.so"),
            ndkDir.resolve("sources/cxx-stl/llvm-libc++/libs/${abi.abi}/libc++_shared.so"),
        )
        val libcxxShared = libcxxCandidates.firstOrNull(File::isFile)
            ?: error(
                "libc++_shared.so for ${abi.abi} not found. Checked: " +
                    libcxxCandidates.joinToString { it.absolutePath },
            )

        return AndroidToolchain(
            cc = cc,
            cxx = cxx,
            ccArgs = if (hostOs == Os.Windows) listOf("--target=$targetTripleWithApi") else emptyList(),
            cxxArgs = if (hostOs == Os.Windows) listOf("--target=$targetTripleWithApi") else emptyList(),
            ar = ar,
            nm = nm,
            strip = strip,
            ranlib = ranlib,
            sysroot = sysroot,
            libcxxShared = libcxxShared,
            sysrootTriple = sysrootTriple,
            cpuFamily = cpuFamily,
            cpu = cpu,
        )
    }

    fun androidCrossFileContent(abi: AndroidAbi): String {
        val toolchain = androidToolchain(abi)
        fun mesonPath(file: File): String = if (hostOs == Os.Windows) {
            file.absolutePath.replace("\\", "/")
        } else {
            file.absolutePath
        }
        fun shellArray(file: File, args: List<String>): String =
            if (args.isEmpty()) {
                "'${mesonPath(file)}'"
            } else {
                (listOf(mesonPath(file)) + args)
                    .joinToString(prefix = "[", postfix = "]", separator = ", ") { "'$it'" }
            }

        val ffmpegPkgConfigDir = ffmpegInstallDir(nativebuild.androidTargetName(abi)).resolve("lib/pkgconfig")
        val sysrootFlag = "--sysroot=${mesonPath(toolchain.sysroot)}"

        return """
            [binaries]
            c = ${shellArray(toolchain.cc, toolchain.ccArgs)}
            cpp = ${shellArray(toolchain.cxx, toolchain.cxxArgs)}
            ar = '${mesonPath(toolchain.ar)}'
            nm = '${mesonPath(toolchain.nm)}'
            strip = '${mesonPath(toolchain.strip)}'
            pkg-config = 'pkg-config'
            pkgconfig = 'pkg-config'
            ranlib = '${mesonPath(toolchain.ranlib)}'

            [built-in options]
            c_args = ['-fPIC', '$sysrootFlag']
            cpp_args = ['-fPIC', '$sysrootFlag']
            c_link_args = ['$sysrootFlag']
            cpp_link_args = ['$sysrootFlag']

            [properties]
            pkg_config_libdir = ['${mesonPath(ffmpegPkgConfigDir)}']
            needs_exe_wrapper = true

            [host_machine]
            system = 'android'
            cpu_family = '${toolchain.cpuFamily}'
            cpu = '${toolchain.cpu}'
            endian = 'little'
        """.trimIndent()
    }

    companion object {
        private val ALL_BUILD_VARIANT_FAMILIES = setOf("windows", "linux", "macos", "android")
    }
}
