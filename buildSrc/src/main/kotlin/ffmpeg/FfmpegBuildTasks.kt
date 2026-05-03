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
import nativebuild.PrepareSourceTreeTask
import nativebuild.resolveNdkDir
import nativebuild.resolveMsys2Dir
import nativebuild.toMsysPath
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import java.io.File

internal fun registerHostFfmpegTasks(context: FfmpegBuildContext) {
    val project = context.project
    val sourceTemplateDir = project.layout.buildDirectory.dir("ffmpeg-source-template")

    val applyPatchesTask = project.tasks.register<Exec>("applyFfmpegPatches") {
        group = "ffmpeg"
        description = "Apply patches to the FFmpeg submodule source tree"
        enabled = context.ffmpegPatch.exists()
        
        commandLine("git", "apply", context.ffmpegPatch.absolutePath)
        workingDir = context.ffmpegSrcDir
    }

    val revertPatchesTask = project.tasks.register<Exec>("revertFfmpegPatches") {
        group = "ffmpeg"
        description = "Revert patches from the FFmpeg submodule source tree"
        enabled = context.ffmpegPatch.exists()
        
        commandLine("git", "checkout", "--", ".")
        workingDir = context.ffmpegSrcDir
    }

    val sourceTemplateTask = project.tasks.register<PrepareSourceTreeTask>("prepareFfmpegSourceTemplate") {
        group = "ffmpeg"
        description = "Create a stable FFmpeg source snapshot for this build"
        dependsOn(applyPatchesTask)
        finalizedBy(revertPatchesTask)
        sourceDir.set(context.ffmpegSrcDir)
        outputDir.set(sourceTemplateDir)
        markerFileRelativePath.set("configure")
        sourceDisplayName.set("FFmpeg")
        preserveExecutablePermissions.set(true)
    }
    var previousTargetTask: TaskProvider<out Task>? = null
    when (context.hostOs) {
        Os.Windows -> {
            if (context.isBuildVariantEnabled("windows")) {
                previousTargetTask = registerFfmpegTasks(context, context.windowsTarget(), sourceTemplateTask, sourceTemplateDir, previousTargetTask)
            } else {
                project.logger.lifecycle(
                    "Skipping FFmpeg windows targets: ${context.buildProperties.buildVariantPropertyName} does not include 'windows'.",
                )
            }
            previousTargetTask = registerAndroidTargetsIfAvailable(context, sourceTemplateTask, sourceTemplateDir, previousTargetTask)
        }

        Os.Linux -> {
            if (context.isBuildVariantEnabled("linux")) {
                previousTargetTask = registerFfmpegTasks(context, context.linuxX64Target, sourceTemplateTask, sourceTemplateDir, previousTargetTask)
            } else {
                project.logger.lifecycle(
                    "Skipping FFmpeg linux targets: ${context.buildProperties.buildVariantPropertyName} does not include 'linux'.",
                )
            }
            previousTargetTask = registerAndroidTargetsIfAvailable(context, sourceTemplateTask, sourceTemplateDir, previousTargetTask)
        }

        Os.MacOS -> {
            if (context.isBuildVariantEnabled("macos")) {
                when (context.hostArch) {
                    Arch.AARCH64 -> previousTargetTask = registerFfmpegTasks(context, context.macosArm64Target, sourceTemplateTask, sourceTemplateDir, previousTargetTask)
                    Arch.X86_64 -> previousTargetTask = registerFfmpegTasks(context, context.macosX64Target, sourceTemplateTask, sourceTemplateDir, previousTargetTask)
                    else -> throw GradleException("Failed to configure FFmpeg tasks, unknown macOS host.")
                }
            } else {
                project.logger.lifecycle(
                    "Skipping FFmpeg macos targets: ${context.buildProperties.buildVariantPropertyName} does not include 'macos'.",
                )
            }
            if (context.isBuildVariantEnabled("ios")) {
                previousTargetTask = registerFfmpegTasks(context, context.iosArm64Target, sourceTemplateTask, sourceTemplateDir, previousTargetTask)
                previousTargetTask = registerFfmpegTasks(context, context.iosSimulatorArm64Target, sourceTemplateTask, sourceTemplateDir, previousTargetTask)
                registerAppleXcframeworkTask(context)
            } else {
                project.logger.lifecycle(
                    "Skipping FFmpeg ios targets: ${context.buildProperties.buildVariantPropertyName} does not include 'ios'.",
                )
            }
            previousTargetTask = registerAndroidTargetsIfAvailable(context, sourceTemplateTask, sourceTemplateDir, previousTargetTask)
        }

        Os.Unknown -> project.logger.warn("Unknown host OS – no FFmpeg build targets registered.")
    }

    project.tasks.register("ffmpegBuildAll") {
        group = "ffmpeg"
        description = "Build FFmpeg for all targets available on the current host OS"
        dependsOn(project.tasks.matching {
            it.name.startsWith("ffmpegAssemble") ||
                it.name.startsWith("ffmpegAppleFramework") ||
                it.name == "ffmpegCreateAppleXcframework"
        })
    }
}

internal fun FfmpegBuildContext.windowsTarget(): FfmpegBuildTarget = FfmpegBuildTarget(
    name = "WindowsX64",
    extraFlags = listOf(
        "--arch=x86_64",
        "--target-os=mingw32",
        "--cc=${msys2Dir.resolve("ucrt64/bin/gcc.exe").absolutePath.toMsysPath()}",
        "--cxx=${msys2Dir.resolve("ucrt64/bin/g++.exe").absolutePath.toMsysPath()}",
        "--enable-openssl",
        "--enable-protocol=udp",
        "--enable-protocol=tcp",
        "--enable-protocol=tls",
        "--enable-protocol=http",
        "--enable-protocol=https",
    ),
    env = mapOf("MSYSTEM" to "UCRT64"),
    shell = msys2Dir.resolve("usr/bin/bash.exe").absolutePath,
    libExtension = "dll",
    libPrefix = "",
)

private fun registerAndroidTargetsIfAvailable(
    context: FfmpegBuildContext,
    sourceTemplateTask: TaskProvider<PrepareSourceTreeTask>,
    templateSnapshotDir: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    previousTargetTask: TaskProvider<out Task>?,
): TaskProvider<out Task>? {
    if (!context.isBuildVariantEnabled("android")) {
        context.project.logger.lifecycle(
            "Skipping FFmpeg android targets: ${context.buildProperties.buildVariantPropertyName} does not include 'android'.",
        )
        return previousTargetTask
    }

    val ndkAvailable = runCatching { context.project.resolveNdkDir() }.isSuccess
    if (!ndkAvailable) {
        context.project.logger.warn("Android NDK not found – skipping Android FFmpeg targets. Set ndk.dir or ANDROID_NDK_HOME to enable.")
        return previousTargetTask
    }

    var lastTask = previousTargetTask
    context.androidAbis.forEach { abi ->
        lastTask = registerFfmpegTasks(context, context.androidTarget(abi), sourceTemplateTask, templateSnapshotDir, lastTask)
    }
    return lastTask
}

private fun registerFfmpegTasks(
    context: FfmpegBuildContext,
    target: FfmpegBuildTarget,
    sourceTemplateTask: TaskProvider<PrepareSourceTreeTask>,
    templateSnapshotDir: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    previousTargetTask: TaskProvider<out Task>?,
): TaskProvider<out Task> {
    val project = context.project
    val buildDir = project.layout.buildDirectory.dir("ffmpeg/${target.name}")
    val installDir = project.layout.buildDirectory.dir("ffmpeg/${target.name}/install")
    val configStamp = project.layout.buildDirectory.file("ffmpeg/${target.name}/.config_stamp")
    val buildStamp = project.layout.buildDirectory.file("ffmpeg/${target.name}/.build_stamp")
    val msys2Dir = if (context.hostOs == Os.Windows) context.project.resolveMsys2Dir() else null

    val configureTask = project.tasks.register<FfmpegConfigureTask>("ffmpegConfigure${target.name}") {
        group = "ffmpeg"
        description = "Run FFmpeg configure for ${target.name}"
        dependsOn(sourceTemplateTask)
        this.sourceTemplateDir.set(templateSnapshotDir)
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
        hostOsName.set(context.hostOs.name)
        buildDirPath.set(buildDir)
        installDirPath.set(installDir.map { it.asFile.absolutePath })
        this.buildStamp.set(buildStamp)
    }

    val assembleTask = project.tasks.register<FfmpegAssembleTask>("ffmpegAssemble${target.name}") {
        group = "ffmpeg"
        description = "Assemble FFmpeg outputs for ${target.name}"
        dependsOn(buildTask)
        if (previousTargetTask != null) {
            mustRunAfter(previousTargetTask)
        }
        targetName.set(target.name)
        libExtension.set(target.libExtension)
        libPrefix.set(target.libPrefix)
        ffmpegLibNames.set(context.ffmpegLibNames)
        buildDirPath.set(buildDir)
        this.installDir.set(installDir)
        this.outputDir.set(project.layout.buildDirectory.dir("ffmpeg-output/${target.name}"))
        if (target.name.startsWith("Android") || target.name == "LinuxX64" || target.name == "MacosArm64" || target.name == "MacosX64" || target.name == "WindowsX64") {
            commandWrapperSource.set(context.commandWrapperSource)
            jniWrapperSource.set(context.jniWrapperSource)
        }
        if (msys2Dir != null) {
            this.msys2Dir.set(msys2Dir)
        }
    }

    if (target.name == "IosArm64" || target.name == "IosSimulatorArm64") {
        val frameworkTask = project.tasks.register<FfmpegAppleFrameworkTask>("ffmpegAppleFramework${target.name}") {
            group = "ffmpeg"
            description = "Build Apple framework for ${target.name}"
            dependsOn(assembleTask)
            if (previousTargetTask != null) {
                mustRunAfter(previousTargetTask)
            }
            targetName.set(target.name)
            frameworkName.set(context.appleFrameworkName)
            ffmpegLibNames.set(context.ffmpegLibNames)
            buildDirPath.set(buildDir)
            this.installDir.set(installDir)
            wrapperSource.set(context.commandWrapperSource)
            publicHeaderSource.set(context.applePublicHeaderSource)
            outputDir.set(project.layout.buildDirectory.dir("apple-framework/${target.name}/${context.appleFrameworkName}.framework"))
        }
        return frameworkTask
    }

    return assembleTask
}

private fun registerAppleXcframeworkTask(context: FfmpegBuildContext) {
    val project = context.project
    if (project.tasks.names.contains("ffmpegAppleFrameworkIosArm64").not() ||
        project.tasks.names.contains("ffmpegAppleFrameworkIosSimulatorArm64").not()
    ) {
        return
    }

    project.tasks.register<FfmpegAppleXcframeworkTask>("ffmpegCreateAppleXcframework") {
        group = "ffmpeg"
        description = "Create Apple XCFramework for FFmpeg runtime"
        dependsOn("ffmpegAppleFrameworkIosArm64", "ffmpegAppleFrameworkIosSimulatorArm64")
        frameworkName.set(context.appleFrameworkName)
        iosDeviceFramework.set(
            project.layout.buildDirectory.dir("apple-framework/IosArm64/${context.appleFrameworkName}.framework"),
        )
        iosSimulatorFramework.set(
            project.layout.buildDirectory.dir("apple-framework/IosSimulatorArm64/${context.appleFrameworkName}.framework"),
        )
        outputDir.set(
            project.layout.buildDirectory.dir("apple-xcframework/${context.appleFrameworkName}.xcframework"),
        )
    }
}
