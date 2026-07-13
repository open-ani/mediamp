/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package ffmpeg

import Os
import nativebuild.PatchedSourceTemplateSpec
import nativebuild.registerPatchedSourceTemplate
import nativebuild.resolveMsys2Dir
import nativebuild.resolveNdkDir
import nativebuild.toolchainFingerprint
import org.gradle.api.JavaVersion
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal fun registerHostFfmpegTasks(context: FfmpegBuildContext) {
    val project = context.project
    val sourceTemplateDir = project.layout.buildDirectory.dir("ffmpeg-source-template")

    val sourceTemplateTask = project.registerPatchedSourceTemplate(
        PatchedSourceTemplateSpec(
            taskNameInfix = "Ffmpeg",
            taskGroup = "ffmpeg",
            sourceDisplayName = "FFmpeg",
            patchFile = context.ffmpegPatch,
            sourceDir = context.ffmpegSrcDir,
            outputDir = sourceTemplateDir,
            markerFileRelativePath = "configure",
            revertCommand = listOf("git", "checkout", "--", "."),
            missingSourceMessage = missingFfmpegSourceTreeMessage(context.ffmpegSrcDir),
            preserveExecutablePermissions = true,
        ),
    )

    var previousTargetTask: TaskProvider<out Task>? = null
    context.enabledTargets().forEach { target ->
        previousTargetTask =
            registerFfmpegTasks(context, target, sourceTemplateTask, sourceTemplateDir, previousTargetTask)
    }
    if (context.hostOs == Os.MacOS && context.isBuildVariantEnabled("ios")) {
        registerAppleXcframeworkTask(context)
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

/**
 * The targets buildable on this host, gated by the `mediamp.ffmpeg.buildvariant` property.
 * This is the single dispatch point from host OS to platform variants; the per-variant
 * configuration itself lives in [FfmpegTargets.kt](FfmpegBuildTarget).
 */
private fun FfmpegBuildContext.enabledTargets(): List<FfmpegBuildTarget> = buildList {
    fun skip(family: String) = project.logger.lifecycle(
        "Skipping FFmpeg $family targets: ${buildProperties.buildVariantPropertyName} does not include '$family'.",
    )

    when (hostOs) {
        Os.Windows -> if (isBuildVariantEnabled("windows")) add(hostWindowsTarget()) else skip("windows")
        Os.Linux -> if (isBuildVariantEnabled("linux")) add(linuxX64Target()) else skip("linux")
        Os.MacOS -> {
            if (isBuildVariantEnabled("macos")) add(hostMacosTarget()) else skip("macos")
            if (isBuildVariantEnabled("ios")) {
                add(iosArm64Target())
                add(iosSimulatorArm64Target())
            } else {
                skip("ios")
            }
        }

        Os.Unknown -> project.logger.warn("Unknown host OS – no FFmpeg build targets registered.")
    }

    if (hostOs != Os.Unknown) {
        if (!isBuildVariantEnabled("android")) {
            skip("android")
        } else if (runCatching { project.resolveNdkDir() }.isFailure) {
            project.logger.warn(
                "Android NDK not found – skipping Android FFmpeg targets. Set ndk.dir or ANDROID_NDK_HOME to enable.",
            )
        } else {
            androidAbis.forEach { abi -> add(androidTarget(abi)) }
        }
    }
}

private fun registerFfmpegTasks(
    context: FfmpegBuildContext,
    target: FfmpegBuildTarget,
    sourceTemplateTask: TaskProvider<nativebuild.PrepareSourceTreeTask>,
    templateSnapshotDir: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    previousTargetTask: TaskProvider<out Task>?,
): TaskProvider<out Task> {
    val project = context.project
    val buildDir = project.layout.buildDirectory.dir("ffmpeg/${target.name}")
    val installDir = project.layout.buildDirectory.dir("ffmpeg/${target.name}/install")
    val fftoolsDir = buildDir.map { it.dir("fftools") }
    val configStamp = project.layout.buildDirectory.file("ffmpeg/${target.name}/.config_stamp")
    val buildStamp = project.layout.buildDirectory.file("ffmpeg/${target.name}/.build_stamp")
    val msys2Dir = if (context.hostOs == Os.Windows) context.project.resolveMsys2Dir() else null
    val toolchainFingerprint = project.toolchainFingerprint(target.toolchainProbes)
    val toolchainConfigSummary = buildDir.map { sanitizedFfmpegToolchainSummary(it.asFile) }

    val configureTask = project.tasks.register<FfmpegConfigureTask>("ffmpegConfigure${target.name}") {
        group = "ffmpeg"
        description = "Run FFmpeg configure for ${target.name}"
        dependsOn(sourceTemplateTask)
        this.sourceTemplateDir.set(templateSnapshotDir)
        configureFlags.set(commonConfigureFlags + target.configureFlags)
        shell.set(target.shell)
        envVars.set(target.env)
        hostOsName.set(context.hostOs.name)
        buildDirPath.set(buildDir)
        stagedSourceDir.set(buildDir.map { it.dir("source") })
        installPrefix.set(installDir.map { it.asFile.absolutePath })
        this.configStamp.set(configStamp)
        if (msys2Dir != null) {
            this.msys2Dir.set(msys2Dir)
        }
        msys2Packages.set(target.msys2Packages)
    }

    val buildTask = project.tasks.register<FfmpegBuildTask>("ffmpegBuild${target.name}") {
        group = "ffmpeg"
        description = "Build FFmpeg for ${target.name}"
        dependsOn(configureTask)
        stagedSourceDir.set(configureTask.flatMap { it.stagedSourceDir })
        this.configStamp.set(configStamp)
        shell.set(target.shell)
        envVars.set(target.env)
        makeJobs.set(context.makeJobs)
        hostOsName.set(context.hostOs.name)
        this.toolchainFingerprint.set(toolchainFingerprint)
        buildDirPath.set(buildDir)
        this.installDir.set(installDir)
        fftoolsObjectsDir.set(fftoolsDir)
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
        this.installDir.set(buildTask.flatMap { it.installDir })
        fftoolsObjectsDir.set(buildTask.flatMap { it.fftoolsObjectsDir })
        this.toolchainConfigSummary.set(toolchainConfigSummary)
        this.toolchainFingerprint.set(toolchainFingerprint)
        jdkMajorVersion.set(JavaVersion.current().majorVersion)
        this.outputDir.set(project.layout.buildDirectory.dir("ffmpeg-output/${target.name}"))
        if (target.jniWrapperName != null) {
            jniWrapperName.set(target.jniWrapperName)
            commandWrapperSource.set(context.commandWrapperSource)
            jniWrapperSource.set(context.jniWrapperSource)
        }
        jniWrapperLinkFlags.set(target.jniWrapperLinkFlags)
        jniWrapperExtraLibs.set(target.jniWrapperExtraLibs)
        jniWrapperUseJdkIncludes.set(target.jniWrapperUseJdkIncludes)
        msysSubsystem.set(target.msysSubsystem)
        collectWindowsRuntime.set(target.collectWindowsRuntime)
        rewriteAppleInstallNames.set(target.rewriteAppleInstallNames)
        bundleFfmpegExecutable.set(target.bundleFfmpegExecutable)
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
            this.installDir.set(buildTask.flatMap { it.installDir })
            fftoolsObjectsDir.set(buildTask.flatMap { it.fftoolsObjectsDir })
            this.toolchainConfigSummary.set(toolchainConfigSummary)
            this.toolchainFingerprint.set(toolchainFingerprint)
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
