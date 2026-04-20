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
import nativebuild.androidNdkHostTag
import nativebuild.androidTargetName
import nativebuild.includesBuildVariant
import nativebuild.resolveAndroidAbis
import nativebuild.resolveEnabledBuildVariantFamilies
import nativebuild.resolveMsys2Dir
import nativebuild.resolveNdkDir
import nativebuild.toMsysPath
import org.gradle.api.Project
import java.io.File
import java.util.Locale

internal data class MpvBuildTarget(
    val name: String,
    val family: String,
    val ffmpegTargetName: String,
    val mesonOptions: List<String>,
    val shell: String = "bash",
    val env: Map<String, String> = emptyMap(),
    val androidAbi: AndroidAbi? = null,
    val wrapDependencies: List<String> = emptyList(),
    val wrapFiles: Map<String, String> = emptyMap(),
    val msys2Packages: List<String> = emptyList(),
)

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

internal class MpvBuildContext(
    val project: Project,
) {
    val mpvProjectDir: File = project.projectDir
    val mpvSrcDir: File = project.projectDir.resolve("mpv")
    val ffmpegProject: Project = project.rootProject.project(":mediamp-ffmpeg")

    val hostOs: Os = getOs()
    val hostArch: Arch = getArch()

    val enabledBuildVariantFamilies: Set<String> =
        project.resolveEnabledBuildVariantFamilies("mediamp.mpv.buildvariant", ALL_BUILD_VARIANT_FAMILIES)

    val mesonBuildType: String =
        project.getPropertyOrNull("mediamp.mpv.buildtype")?.trim()?.takeIf { it.isNotEmpty() } ?: "release"

    val androidAbis: List<AndroidAbi> =
        project.resolveAndroidAbis(
            propertyName = "mediamp.mpv.androidabis",
            fallbackPropertyName = "mediamp.ffmpeg.androidabis",
            availableAbis = DEFAULT_ANDROID_ABIS,
        )

    val desktopRuntimeTargets: List<DesktopRuntimeTarget> = DEFAULT_DESKTOP_RUNTIME_TARGETS

    val commonMesonOptions: List<String> = buildList {
        add("-Dlibmpv=true")
        add("-Dcplayer=false")
        add("-Dtests=false")
        add("-Dfuzzers=false")
        add("-Dbuild-date=false")
        add("-Ddefault_library=shared")
        add("-Dlua=disabled")
        add("-Djavascript=disabled")
        add("-Dcplugins=disabled")
        add("-Dcdda=disabled")
        add("-Ddvbin=disabled")
        add("-Ddvdnav=disabled")
        add("-Djpeg=disabled")
        add("-Dlcms2=disabled")
        add("-Dlibarchive=disabled")
        add("-Dlibavdevice=disabled")
        add("-Dlibbluray=disabled")
        add("-Drubberband=disabled")
        add("-Dsdl2-audio=disabled")
        add("-Dsdl2-video=disabled")
        add("-Dsdl2-gamepad=disabled")
        add("-Duchardet=disabled")
        add("-Dvapoursynth=disabled")
        add("-Dzimg=disabled")
        add("-Dhtml-build=disabled")
        add("-Dmanpage-build=disabled")
        add("-Dpdf-build=disabled")
        add("-Dvulkan=disabled")
        add("-Dshaderc=disabled")
        add("-Dspirv-cross=disabled")
        add("-Dcuda-hwaccel=disabled")
        add("-Dcuda-interop=disabled")
        add("-Dd3d9-hwaccel=disabled")
        add("-Dvaapi=disabled")
        add("-Dvaapi-drm=disabled")
        add("-Dvaapi-wayland=disabled")
        add("-Dvaapi-win32=disabled")
        add("-Dvaapi-x11=disabled")
        add("-Dvdpau=disabled")
        add("-Dvdpau-gl-x11=disabled")
        add("-Dxv=disabled")
        add("-Dwayland=disabled")
        add("-Ddrm=disabled")
        add("-Dgbm=disabled")
        add("-Dsixel=disabled")
        add("-Dcaca=disabled")
        add("-Dplain-gl=enabled")
        add("-Dzlib=enabled")
    }

    fun isBuildVariantEnabled(family: String): Boolean =
        enabledBuildVariantFamilies.includesBuildVariant(family)

    fun hostDesktopTargetName(): String? = when (hostOs) {
        Os.Windows -> "WindowsX64"
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

    fun windowsTarget(): MpvBuildTarget {
        val msysShell = project.resolveMsys2Dir().resolve("usr/bin/bash.exe").absolutePath
        return MpvBuildTarget(
            name = "WindowsX64",
            family = "windows",
            ffmpegTargetName = "WindowsX64",
            shell = msysShell,
            env = mapOf("MSYSTEM" to "UCRT64"),
            msys2Packages = listOf(
                "git",
                "mingw-w64-ucrt-x86_64-ca-certificates",
                "mingw-w64-ucrt-x86_64-gcc",
                "mingw-w64-ucrt-x86_64-libass",
                "mingw-w64-ucrt-x86_64-libplacebo",
                "mingw-w64-ucrt-x86_64-meson",
                "mingw-w64-ucrt-x86_64-ninja",
                "mingw-w64-ucrt-x86_64-pkgconf",
                "mingw-w64-ucrt-x86_64-python-certifi",
                "mingw-w64-ucrt-x86_64-python",
                "mingw-w64-ucrt-x86_64-shaderc",
                "mingw-w64-ucrt-x86_64-spirv-cross",
            ),
            mesonOptions = commonMesonOptions + listOf(
                "-Dgl=enabled",
                "-Dgl-win32=enabled",
                "-Degl=disabled",
                "-Degl-x11=disabled",
                "-Degl-android=disabled",
                "-Dshaderc=enabled",
                "-Dspirv-cross=enabled",
                "-Dd3d-hwaccel=enabled",
                "-Dd3d11=enabled",
                "-Ddirect3d=enabled",
                "-Dwasapi=enabled",
                "-Dwin32-smtc=disabled",
                "-Dx11=disabled",
                "-Daudiotrack=disabled",
                "-Dopensles=disabled",
                "-Daaudio=disabled",
            ),
        )
    }

    val linuxX64Target: MpvBuildTarget = MpvBuildTarget(
        name = "LinuxX64",
        family = "linux",
        ffmpegTargetName = "LinuxX64",
        mesonOptions = commonMesonOptions + listOf(
            "-Dgl=enabled",
            "-Dgl-x11=enabled",
            "-Degl=enabled",
            "-Degl-x11=enabled",
            "-Dx11=enabled",
            "-Dd3d11=disabled",
            "-Ddirect3d=disabled",
            "-Dwasapi=disabled",
            "-Daudiotrack=disabled",
            "-Dopensles=disabled",
            "-Daaudio=disabled",
        ),
    )

    val macosArm64Target: MpvBuildTarget = macosTarget(
        name = "MacosArm64",
        arch = "arm64",
        swiftTarget = "arm64-apple-macos12.0",
    )

    val macosX64Target: MpvBuildTarget = macosTarget(
        name = "MacosX64",
        arch = "x86_64",
        swiftTarget = "x86_64-apple-macos12.0",
    )

    fun androidTarget(abi: AndroidAbi): MpvBuildTarget {
        val wrapFiles = mapOf(
            "subprojects/libplacebo.wrap" to """
                [wrap-git]
                url = https://code.videolan.org/videolan/libplacebo.git
                revision = master
                depth = 1
                clone-recursive = true
            """.trimIndent(),
            "subprojects/libass.wrap" to """
                [wrap-git]
                url = https://github.com/libass/libass
                revision = master
                depth = 1
            """.trimIndent(),
        )

        return MpvBuildTarget(
            name = androidTargetName(abi),
            family = "android",
            ffmpegTargetName = androidTargetName(abi),
            androidAbi = abi,
            shell = if (hostOs == Os.Windows) project.resolveMsys2Dir().resolve("usr/bin/bash.exe").absolutePath else "bash",
            env = if (hostOs == Os.Windows) mapOf("MSYSTEM" to "UCRT64") else emptyMap(),
            wrapDependencies = listOf(
                "expat",
                "freetype2",
                "fribidi",
                "harfbuzz",
                "libpng",
                "zlib",
            ),
            wrapFiles = wrapFiles,
            msys2Packages = if (hostOs == Os.Windows) {
                listOf(
                    "git",
                    "mingw-w64-ucrt-x86_64-ca-certificates",
                    "mingw-w64-ucrt-x86_64-gcc",
                    "mingw-w64-ucrt-x86_64-meson",
                    "mingw-w64-ucrt-x86_64-ninja",
                    "mingw-w64-ucrt-x86_64-pkgconf",
                    "mingw-w64-ucrt-x86_64-python-certifi",
                    "mingw-w64-ucrt-x86_64-python",
                )
            } else {
                emptyList()
            },
            mesonOptions = commonMesonOptions + listOf(
                "--force-fallback-for=libass,libplacebo,expat,freetype2,fribidi,harfbuzz,libpng,zlib",
                "-Dgl=enabled",
                "-Degl=disabled",
                "-Degl-x11=disabled",
                "-Degl-android=enabled",
                "-Dx11=disabled",
                "-Dd3d11=disabled",
                "-Ddirect3d=disabled",
                "-Dwasapi=disabled",
                "-Daudiotrack=enabled",
                "-Dopensles=enabled",
                "-Daaudio=disabled",
                "-Dandroid-media-ndk=enabled",
                "-Dlibass:require-system-font-provider=false",
                "-Dlibplacebo:vulkan=disabled",
                "-Dlibplacebo:lcms=disabled",
                "-Dlibplacebo:demos=false",
            ),
        )
    }

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

        val ffmpegPkgConfigDir = ffmpegInstallDir(androidTargetName(abi)).resolve("lib/pkgconfig")
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

    private fun macosTarget(
        name: String,
        arch: String,
        swiftTarget: String,
    ): MpvBuildTarget {
        val commonFlags = "-arch $arch -mmacosx-version-min=12.0"
        val objcArgs = "$commonFlags -Wno-error=deprecated -Wno-error=deprecated-declarations"
        return MpvBuildTarget(
            name = name,
            family = "macos",
            ffmpegTargetName = name,
            env = mapOf(
                "CC" to "clang",
                "CXX" to "clang++",
                "CFLAGS" to commonFlags,
                "CXXFLAGS" to commonFlags,
                "OBJCFLAGS" to objcArgs,
                "LDFLAGS" to commonFlags,
            ),
            mesonOptions = commonMesonOptions + listOf(
                "-Dcocoa=enabled",
                "-Dcoreaudio=enabled",
                "-Daudiounit=disabled",
                "-Davfoundation=disabled",
                "-Dgl=enabled",
                "-Dgl-cocoa=enabled",
                "-Degl=disabled",
                "-Degl-x11=disabled",
                "-Degl-android=disabled",
                "-Dx11=disabled",
                "-Dgl-x11=disabled",
                "-Dd3d11=disabled",
                "-Ddirect3d=disabled",
                "-Dwasapi=disabled",
                "-Daudiotrack=disabled",
                "-Dopensles=disabled",
                "-Daaudio=disabled",
                "-Dvideotoolbox-gl=enabled",
                "-Dvideotoolbox-pl=disabled",
                "-Dswift-build=enabled",
                "-Dmacos-cocoa-cb=enabled",
                "-Dmacos-media-player=disabled",
                "-Dmacos-touchbar=disabled",
                "-Dobjc_args=$objcArgs",
                "-Dswift-flags=-target $swiftTarget",
            ),
        )
    }

    companion object {
        private val ALL_BUILD_VARIANT_FAMILIES = setOf("windows", "linux", "macos", "android")
    }
}
