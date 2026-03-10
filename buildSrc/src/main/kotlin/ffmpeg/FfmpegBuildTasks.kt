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
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.register
import java.io.File

internal fun registerHostFfmpegTasks(context: FfmpegBuildContext) {
    val project = context.project
    when (context.hostOs) {
        Os.Windows -> {
            if (context.isBuildVariantEnabled("windows")) {
                registerFfmpegTasks(context, context.windowsTarget())
            } else {
                project.logger.lifecycle("Skipping FFmpeg windows targets: mediamp.ffmpeg.buildvariant does not include 'windows'.")
            }
            registerAndroidTargetsIfAvailable(context)
        }

        Os.Linux -> {
            if (context.isBuildVariantEnabled("linux")) {
                registerFfmpegTasks(context, context.linuxX64Target)
            } else {
                project.logger.lifecycle("Skipping FFmpeg linux targets: mediamp.ffmpeg.buildvariant does not include 'linux'.")
            }
            registerAndroidTargetsIfAvailable(context)
        }

        Os.MacOS -> {
            if (context.isBuildVariantEnabled("macos")) {
                when (context.hostArch) {
                    Arch.AARCH64 -> registerFfmpegTasks(context, context.macosArm64Target)
                    Arch.X86_64 -> registerFfmpegTasks(context, context.macosX64Target)
                    else -> throw GradleException("Failed to configure FFmpeg tasks, unknown macOS host.")
                }
            } else {
                project.logger.lifecycle("Skipping FFmpeg macos targets: mediamp.ffmpeg.buildvariant does not include 'macos'.")
            }
            if (context.isBuildVariantEnabled("ios")) {
                registerFfmpegTasks(context, context.iosArm64Target)
                registerFfmpegTasks(context, context.iosSimulatorArm64Target)
            } else {
                project.logger.lifecycle("Skipping FFmpeg ios targets: mediamp.ffmpeg.buildvariant does not include 'ios'.")
            }
            registerAndroidTargetsIfAvailable(context)
        }

        Os.Unknown -> project.logger.warn("Unknown host OS – no FFmpeg build targets registered.")
    }

    project.tasks.register("ffmpegBuildAll") {
        group = "ffmpeg"
        description = "Build FFmpeg for all targets available on the current host OS"
        dependsOn(project.tasks.matching { it.name.startsWith("ffmpegAssemble") })
    }
}

internal fun FfmpegBuildContext.windowsTarget(): FfmpegBuildTarget = FfmpegBuildTarget(
    name = "WindowsX64",
    extraFlags = listOf(
        "--arch=x86_64",
        "--target-os=mingw32",
        "--cc=${msys2Dir.resolve("ucrt64/bin/gcc.exe").absolutePath.toMsysPath()}",
        "--cxx=${msys2Dir.resolve("ucrt64/bin/g++.exe").absolutePath.toMsysPath()}",
    ),
    env = mapOf("MSYSTEM" to "UCRT64"),
    shell = msys2Dir.resolve("usr/bin/bash.exe").absolutePath,
    libExtension = "dll",
    libPrefix = "",
)

private fun registerAndroidTargetsIfAvailable(
    context: FfmpegBuildContext,
) {
    if (!context.isBuildVariantEnabled("android")) {
        context.project.logger.lifecycle("Skipping FFmpeg android targets: mediamp.ffmpeg.buildvariant does not include 'android'.")
        return
    }

    val ndkAvailable = runCatching { context.resolveNdkDir() }.isSuccess
    if (!ndkAvailable) {
        context.project.logger.warn("Android NDK not found – skipping Android FFmpeg targets. Set ndk.dir or ANDROID_NDK_HOME to enable.")
        return
    }

    context.androidAbis.forEach { abi ->
        registerFfmpegTasks(context, context.androidTarget(abi))
    }
}

private fun registerFfmpegTasks(
    context: FfmpegBuildContext,
    target: FfmpegBuildTarget,
) {
    val project = context.project
    val buildDir = project.layout.buildDirectory.dir("ffmpeg/${target.name}")
    val installDir = project.layout.buildDirectory.dir("ffmpeg/${target.name}/install")
    val targetSourceDir = project.layout.buildDirectory.dir("ffmpeg-source/${target.name}")
    val targetConfigureFile = project.layout.buildDirectory.file("ffmpeg-source/${target.name}/configure")
    val configStamp = project.layout.buildDirectory.file("ffmpeg/${target.name}/.config_stamp")
    val buildStamp = project.layout.buildDirectory.file("ffmpeg/${target.name}/.build_stamp")
    val msys2Dir = if (context.hostOs == Os.Windows) context.resolveMsys2Dir() else null

    val targetSourceTask = project.tasks.register<PrepareFfmpegSourceTask>("prepareFfmpegSource${target.name}") {
        group = "ffmpeg"
        description = "Copy FFmpeg source tree for ${target.name}"
        sourceDir.set(context.ffmpegSrcDir)
        outputDir.set(targetSourceDir)
        configureFile.set(targetConfigureFile)
    }

    val configureTask = project.tasks.register<FfmpegConfigureTask>("ffmpegConfigure${target.name}") {
        group = "ffmpeg"
        description = "Run FFmpeg configure for ${target.name}"
        dependsOn(targetSourceTask)
        configureFile.set(targetConfigureFile)
        configureFlags.set(context.commonConfigureFlags + target.extraFlags)
        shell.set(target.shell)
        envVars.set(target.env)
        hostOsName.set(context.hostOs.name)
        buildDirPath.set(buildDir)
        installPrefix.set(installDir.map { it.asFile.absolutePath })
        this.configStamp.set(configStamp)
        if (msys2Dir != null) {
            this.msys2Dir.set(msys2Dir)
        }
    }

    val buildTask = project.tasks.register<FfmpegBuildTask>("ffmpegBuild${target.name}") {
        group = "ffmpeg"
        description = "Build FFmpeg for ${target.name}"
        dependsOn(configureTask)
        this.configStamp.set(configStamp)
        shell.set(target.shell)
        envVars.set(target.env)
        makeJobs.set(context.makeJobs)
        buildDirPath.set(buildDir)
        this.buildStamp.set(buildStamp)
    }

    project.tasks.register<FfmpegAssembleTask>("ffmpegAssemble${target.name}") {
        group = "ffmpeg"
        description = "Assemble FFmpeg outputs for ${target.name}"
        dependsOn(buildTask)
        targetName.set(target.name)
        libExtension.set(target.libExtension)
        libPrefix.set(target.libPrefix)
        ffmpegLibNames.set(context.ffmpegLibNames)
        buildDirPath.set(buildDir)
        this.installDir.set(installDir)
        this.outputDir.set(project.layout.buildDirectory.dir("ffmpeg-output/${target.name}"))
        if (target.name == "IosArm64" || target.name == "IosSimulatorArm64") {
            wrapperSource.set(context.wrapperSource)
        }
        if (msys2Dir != null) {
            this.msys2Dir.set(msys2Dir)
        }
    }
}

internal fun restoreExecutablePermissions(sourceDir: File, targetDir: File) {
    sourceDir.walkTopDown()
        .filter { src ->
            src.isFile && (
                src.canExecute() ||
                    src.name == "configure" ||
                    src.extension == "sh"
                )
        }
        .forEach { src ->
            val relative = src.relativeTo(sourceDir)
            val target = targetDir.resolve(relative.path)
            if (target.exists()) {
                target.setExecutable(true)
            }
        }
}
