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
import getArch
import getOs
import nativebuild.AndroidAbi
import nativebuild.DEFAULT_ANDROID_ABIS
import nativebuild.DEFAULT_DESKTOP_RUNTIME_TARGETS
import nativebuild.DesktopRuntimeTarget
import nativebuild.FFMPEG_NATIVE_BUILD_PROPERTIES
import nativebuild.NativeBuildProperties
import nativebuild.includesBuildVariant
import nativebuild.resolveAndroidAbis
import nativebuild.resolveEnabledBuildVariantFamilies
import nativebuild.resolveMsys2Dir
import org.gradle.api.Project
import java.io.File

internal data class AppleRuntimeTarget(
    val artifactIdSuffix: String,
    val publicationSuffix: String,
    val ffmpegTargetName: String,
)

internal fun missingFfmpegSourceTreeMessage(sourceDir: File): String = """
    FFmpeg source tree is missing at ${sourceDir.absolutePath}.

    The FFmpeg sources are a git submodule and have not been checked out.

    If you already cloned this repository, run:
      git submodule update --init --recursive mediamp-ffmpeg/ffmpeg

    For a fresh checkout, clone with submodules:
      git clone --recursive git@github.com:open-ani/mediamp.git

    Or clone first and initialize submodules afterwards:
      git clone git@github.com:open-ani/mediamp.git
      cd mediamp
      git submodule update --init --recursive
""".trimIndent()

/**
 * Host/project environment for the FFmpeg build. Per-platform build configuration lives
 * in [FfmpegTargets.kt](FfmpegBuildTarget); this class only carries what the target
 * factories and task registration need to look things up.
 */
internal class FfmpegBuildContext(
    val project: Project,
    val ffmpegPatch: File,
) {
    val buildProperties: NativeBuildProperties = FFMPEG_NATIVE_BUILD_PROPERTIES

    val ffmpegSrcDir: File = project.projectDir.resolve("ffmpeg")

    val appleFrameworkName: String = "MediampFFmpegKit"
    val commandWrapperSource: File = project.projectDir.resolve("src/appleMain/c/ffmpegkit_wrapper.c")
    val applePublicHeaderSource: File = project.projectDir.resolve("src/appleMain/include/MediampFFmpegKit.h")
    val jniWrapperSource: File = project.projectDir.resolve("src/jvmMain/c/ffmpegkit_jni.c")

    val enabledBuildVariantFamilies: Set<String> =
        project.resolveEnabledBuildVariantFamilies(buildProperties, ALL_BUILD_VARIANT_FAMILIES)

    val hostOs: Os = getOs()
    val hostArch: Arch = getArch()

    val makeJobs: Int = Runtime.getRuntime().availableProcessors()

    val ffmpegLibNames: List<String> = listOf(
        "avcodec",
        "avdevice",
        "avfilter",
        "avformat",
        "avutil",
        "swresample",
        "swscale",
    )

    val androidAbis: List<AndroidAbi> =
        project.resolveAndroidAbis(buildProperties, availableAbis = DEFAULT_ANDROID_ABIS)

    val desktopRuntimeTargets: List<DesktopRuntimeTarget> = DEFAULT_DESKTOP_RUNTIME_TARGETS

    val appleRuntimeTargets: List<AppleRuntimeTarget> = listOf(
        AppleRuntimeTarget("ios-arm64", "IosArm64", "IosArm64"),
        AppleRuntimeTarget("ios-simulator-arm64", "IosSimulatorArm64", "IosSimulatorArm64"),
    )

    val msys2Dir: File
        get() = project.resolveMsys2Dir()

    fun isBuildVariantEnabled(family: String): Boolean =
        enabledBuildVariantFamilies.includesBuildVariant(family)

    companion object {
        private val ALL_BUILD_VARIANT_FAMILIES = setOf("windows", "linux", "macos", "ios", "android")
    }
}
