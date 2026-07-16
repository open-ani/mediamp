/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package mpv

import Os
import getPropertyOrNull
import nativebuild.AndroidAbi
import nativebuild.androidTargetName
import nativebuild.pathForShell
import nativebuild.resolveMsys2Dir

/**
 * How the mediampv JNI wrapper library is compiled and linked for one target.
 * Consumed verbatim by [MpvJniBuildTask]; there is no per-target logic in the task.
 */
internal data class MpvJniToolchain(
    val compilerCommand: String,
    /** Everything before the include/source arguments: arch flags, language level, `-shared`. */
    val compilerArgs: List<String>,
    /** Everything after the link libraries: `-l` flags, frameworks, rpath. */
    val linkerArgs: List<String>,
    val outputFileName: String,
    /** Source file extensions to compile; `mm` (Objective-C++) is macOS-only. */
    val sourceExtensions: Set<String>,
    /** Whether to compile against the host JDK's JNI headers (false when the NDK provides them). */
    val useJdkIncludes: Boolean,
    /**
     * File name patterns used to locate a link library by base name, e.g. `lib{name}.dll.a`.
     * `{name}` is substituted; a trailing `*` matches any suffix (versioned `.so`).
     */
    val linkLibraryPatterns: List<String>,
    /**
     * Version probe command lines identifying the JNI compiler for the build cache
     * (see [nativebuild.ToolchainFingerprintValueSource]). Must be runnable directly by
     * the JVM (native paths, no MSYS path forms).
     */
    val versionProbes: List<List<String>> = emptyList(),
)

/** Post-assembly fixup making the runtime directory self-contained on each platform. */
internal enum class MpvRuntimePostProcessing {
    /** Recursively copy dependency DLLs from MSYS2 next to the runtime. */
    WINDOWS_COLLECT_DLLS,

    /** Rewrite install names to @loader_path, bundle external dylibs, re-sign. */
    MACOS_BUNDLE_DYLIBS,

    /** Set `RUNPATH=$ORIGIN` so bundled `.so` files resolve their siblings. */
    LINUX_RUNPATH_ORIGIN,

    /** Copy `libc++_shared.so` from the NDK next to the runtime. */
    ANDROID_BUNDLE_LIBCXX,
}

/** Where the runtime libraries live in the assembled output and how they are finished. */
internal data class MpvRuntimeLayout(
    /** Directory (relative to the install prefix) holding the shared libraries: `bin` or `lib`. */
    val runtimeDirName: String,
    val postProcessing: MpvRuntimePostProcessing,
)

/**
 * Complete build description of one mpv target platform: meson configuration, the JNI
 * wrapper toolchain and the runtime layout. Task registration and the task
 * implementations are platform-agnostic consumers of this data.
 */
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
    /**
     * Version probe command lines identifying the meson toolchain and the system
     * libraries mpv links against (see [nativebuild.ToolchainFingerprintValueSource]).
     */
    val toolchainProbes: List<List<String>> = emptyList(),
    val jni: MpvJniToolchain,
    val runtime: MpvRuntimeLayout,
)

// ---------------------------------------------------------------------------------------
// Shared meson options
// ---------------------------------------------------------------------------------------

internal val commonMesonOptions: List<String> = buildList {
    // Debian/Ubuntu 的 meson 默认 libdir 是 multiarch 子目录 (lib/x86_64-linux-gnu),
    // 而打包/JNI 链接统一从 install/lib 取, 显式固定为 lib.
    add("-Dlibdir=lib")
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
    add("-Dlibavdevice=enabled")
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

private const val CPP_STANDARD_FLAG = "-std=c++17"

private fun jniCompilerFallback(context: MpvBuildContext, default: String): String =
    context.project.getPropertyOrNull("CXX")
        ?: System.getenv("CXX")
        ?: default

// ---------------------------------------------------------------------------------------
// Windows
// ---------------------------------------------------------------------------------------

internal fun MpvBuildContext.windowsTarget(): MpvBuildTarget {
    val msys2Root = project.resolveMsys2Dir()
    val msys2Packages = listOf(
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
    )
    return MpvBuildTarget(
        name = "WindowsX64",
        family = "windows",
        ffmpegTargetName = "WindowsX64",
        shell = msys2Root.resolve("usr/bin/bash.exe").absolutePath,
        env = mapOf("MSYSTEM" to "UCRT64"),
        msys2Packages = msys2Packages,
        toolchainProbes = listOf(
            listOf(msys2Root.resolve("usr/bin/pacman.exe").absolutePath, "-Q") + msys2Packages,
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
        jni = MpvJniToolchain(
            compilerCommand = pathForShell(msys2Root.resolve("ucrt64/bin/g++.exe"), true),
            compilerArgs = listOf(CPP_STANDARD_FLAG, "-fPIC", "-shared", "-D_WIN32_WINNT=0x0A00"),
            // D3D11 render path (render_d3d11.cpp); windowscodecs/ole32 are for the WIC PNG readback.
            linkerArgs = listOf("-ld3d11", "-ld3d12", "-ldxgi", "-ldxguid", "-lwindowscodecs", "-lole32"),
            outputFileName = "mediampv.dll",
            sourceExtensions = setOf("cpp"),
            useJdkIncludes = true,
            linkLibraryPatterns = listOf("lib{name}.dll.a", "{name}.lib"),
            versionProbes = listOf(
                listOf(msys2Root.resolve("ucrt64/bin/g++.exe").absolutePath, "--version"),
            ),
        ),
        runtime = MpvRuntimeLayout(
            runtimeDirName = "bin",
            postProcessing = MpvRuntimePostProcessing.WINDOWS_COLLECT_DLLS,
        ),
    )
}

// ---------------------------------------------------------------------------------------
// Linux
// ---------------------------------------------------------------------------------------

internal fun MpvBuildContext.linuxX64Target(): MpvBuildTarget = MpvBuildTarget(
    name = "LinuxX64",
    family = "linux",
    ffmpegTargetName = "LinuxX64",
    toolchainProbes = listOf(
        listOf("cc", "--version"),
        listOf("meson", "--version"),
        listOf("ninja", "--version"),
        listOf("pkg-config", "--modversion", "libass", "libplacebo"),
    ),
    mesonOptions = commonMesonOptions + listOf(
        "-Dgl=enabled",
        "-Dgl-x11=enabled",
        // Keep the existing libmpv EGL-X11 build capability. mediamp's Compose surface
        // bridge implemented in this module supports GLX only; it does not use EGL.
        "-Degl=enabled",
        "-Degl-x11=enabled",
        "-Dx11=enabled",
        // Override the conservative common defaults for Linux. This compiles mpv's
        // CUDA hwdevice and CUDA/OpenGL mapper so NVDEC frames can be registered
        // directly in the GLX producer context without a CPU frame copy.
        "-Dcuda-hwaccel=enabled",
        "-Dcuda-interop=enabled",
        // Enables Intel/AMD VAAPI device creation and the explicit vaapi-copy
        // fallback. Direct VAAPI/OpenGL import is not used by the GLX producer.
        "-Dvaapi=enabled",
        "-Dvaapi-x11=enabled",
        "-Dd3d11=disabled",
        "-Ddirect3d=disabled",
        "-Dwasapi=disabled",
        "-Daudiotrack=disabled",
        "-Dopensles=disabled",
        "-Daaudio=disabled",
    ),
    jni = MpvJniToolchain(
        compilerCommand = jniCompilerFallback(this, default = "g++"),
        compilerArgs = listOf("-pthread", CPP_STANDARD_FLAG, "-fPIC", "-shared"),
        // GLX producer context/pbuffer bridge (glx_context_provider.cpp). libmpv's GL
        // renderer resolves entry points through libGL/dl, while debug PNG output uses zlib.
        linkerArgs = listOf("-pthread", "-Wl,-rpath,\$ORIGIN", "-lGL", "-lX11", "-ldl", "-lz"),
        outputFileName = "libmediampv.so",
        sourceExtensions = setOf("cpp"),
        useJdkIncludes = true,
        linkLibraryPatterns = listOf("lib{name}.so", "lib{name}.so.*"),
        versionProbes = listOf(
            listOf(jniCompilerFallback(this, default = "g++"), "--version"),
        ),
    ),
    runtime = MpvRuntimeLayout(
        runtimeDirName = "lib",
        postProcessing = MpvRuntimePostProcessing.LINUX_RUNPATH_ORIGIN,
    ),
)

// ---------------------------------------------------------------------------------------
// macOS
// ---------------------------------------------------------------------------------------

internal fun MpvBuildContext.macosArm64Target(): MpvBuildTarget = macosTarget(
    name = "MacosArm64",
    arch = "arm64",
    swiftTarget = "arm64-apple-macos12.0",
)

internal fun MpvBuildContext.macosX64Target(): MpvBuildTarget = macosTarget(
    name = "MacosX64",
    arch = "x86_64",
    swiftTarget = "x86_64-apple-macos12.0",
)

private fun MpvBuildContext.macosTarget(
    name: String,
    arch: String,
    swiftTarget: String,
): MpvBuildTarget {
    val commonFlags = "-arch $arch -mmacosx-version-min=12.0"
    val objcArgs = "$commonFlags -Wno-error=deprecated -Wno-error=deprecated-declarations"
    val archArgs = listOf("-arch", arch, "-mmacosx-version-min=12.0")
    return MpvBuildTarget(
        name = name,
        family = "macos",
        ffmpegTargetName = name,
        toolchainProbes = listOf(
            listOf("clang", "--version"),
            listOf("meson", "--version"),
            listOf("ninja", "--version"),
            listOf("pkg-config", "--modversion", "libass", "libplacebo"),
        ),
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
        jni = MpvJniToolchain(
            compilerCommand = jniCompilerFallback(this, default = "clang++"),
            versionProbes = listOf(
                listOf(jniCompilerFallback(this, default = "clang++"), "--version"),
            ),
            // -fobjc-arc is required by render_macos.mm; it has no effect on plain C++ sources.
            compilerArgs = archArgs + listOf("-fobjc-arc", CPP_STANDARD_FLAG, "-fPIC", "-dynamiclib"),
            // Metal/IOSurface render path (render_macos.mm)
            linkerArgs = archArgs + listOf(
                "-framework", "Foundation",
                "-framework", "Metal",
                "-framework", "IOSurface",
                "-framework", "OpenGL",
                "-framework", "QuartzCore",
                "-framework", "CoreGraphics",
                "-framework", "ImageIO",
            ),
            outputFileName = "libmediampv.dylib",
            // Objective-C++ sources are macOS-only (Metal/IOSurface render path).
            sourceExtensions = setOf("cpp", "mm"),
            useJdkIncludes = true,
            linkLibraryPatterns = listOf("lib{name}.dylib"),
        ),
        runtime = MpvRuntimeLayout(
            runtimeDirName = "lib",
            postProcessing = MpvRuntimePostProcessing.MACOS_BUNDLE_DYLIBS,
        ),
    )
}

// ---------------------------------------------------------------------------------------
// Android
// ---------------------------------------------------------------------------------------

internal fun MpvBuildContext.androidTarget(abi: AndroidAbi): MpvBuildTarget {
    val toolchain = androidToolchain(abi)
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

    val msys2Packages = if (hostOs == Os.Windows) {
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
    }
    val toolchainProbes = buildList {
        add(listOf(toolchain.cc.absolutePath, "--version"))
        if (hostOs == Os.Windows) {
            add(listOf(project.resolveMsys2Dir().resolve("usr/bin/pacman.exe").absolutePath, "-Q") + msys2Packages)
        } else {
            add(listOf("meson", "--version"))
            add(listOf("ninja", "--version"))
        }
    }

    return MpvBuildTarget(
        name = androidTargetName(abi),
        family = "android",
        ffmpegTargetName = androidTargetName(abi),
        androidAbi = abi,
        shell = if (hostOs == Os.Windows) project.resolveMsys2Dir().resolve("usr/bin/bash.exe").absolutePath else "bash",
        env = if (hostOs == Os.Windows) mapOf("MSYSTEM" to "UCRT64") else emptyMap(),
        toolchainProbes = toolchainProbes,
        wrapDependencies = listOf(
            "expat",
            "freetype2",
            "fribidi",
            "harfbuzz",
            "libpng",
            "zlib",
        ),
        wrapFiles = wrapFiles,
        msys2Packages = msys2Packages,
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
        jni = MpvJniToolchain(
            compilerCommand = pathForShell(toolchain.cxx, hostOs == Os.Windows),
            versionProbes = listOf(
                listOf(toolchain.cxx.absolutePath, "--version"),
            ),
            compilerArgs = toolchain.cxxArgs +
                "--sysroot=${pathForShell(toolchain.sysroot, hostOs == Os.Windows)}" +
                listOf(CPP_STANDARD_FLAG, "-fPIC", "-shared"),
            linkerArgs = listOf("-landroid", "-llog"),
            outputFileName = "libmediampv.so",
            sourceExtensions = setOf("cpp"),
            // The NDK sysroot ships its own JNI headers.
            useJdkIncludes = false,
            linkLibraryPatterns = listOf("lib{name}.so", "lib{name}.so.*"),
        ),
        runtime = MpvRuntimeLayout(
            runtimeDirName = "lib",
            postProcessing = MpvRuntimePostProcessing.ANDROID_BUNDLE_LIBCXX,
        ),
    )
}
