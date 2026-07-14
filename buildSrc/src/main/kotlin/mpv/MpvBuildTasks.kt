package mpv

import Arch
import Os
import nativebuild.PatchedSourceTemplateSpec
import nativebuild.PrepareSourceTreeTask
import nativebuild.registerPatchedSourceTemplate
import nativebuild.resolveMsys2Dir
import nativebuild.resolveNdkDir
import nativebuild.sanitizeAbsolutePaths
import nativebuild.toolchainFingerprint
import org.gradle.api.JavaVersion
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal fun registerHostMpvTasks(context: MpvBuildContext) {
    val project = context.project
    val sourceTemplateDir = project.layout.buildDirectory.dir("mpv-source-template")

    val sourceTemplateTask = project.registerPatchedSourceTemplate(
        PatchedSourceTemplateSpec(
            taskNameInfix = "Mpv",
            taskGroup = "mpv",
            sourceDisplayName = "mpv",
            patchFile = context.mpvPatch,
            sourceDir = context.mpvSrcDir,
            outputDir = sourceTemplateDir,
            markerFileRelativePath = "meson.build",
            revertCommand = listOf("git", "apply", "--reverse", context.mpvPatch.absolutePath),
            preserveSymbolicLinks = true,
        ),
    )

    var previousTargetTask: TaskProvider<out Task>? = null
    context.enabledTargets().forEach { target ->
        previousTargetTask =
            registerMpvTasks(context, target, sourceTemplateTask, sourceTemplateDir, previousTargetTask)
    }

    project.tasks.register("mpvBuildAll") {
        group = "mpv"
        description = "Build mpv for all targets available on the current host OS"
        dependsOn(project.tasks.matching { it.name.startsWith("mpvAssemble") })
    }
}

/**
 * The targets buildable on this host, gated by the `mediamp.mpv.buildvariant` property.
 * This is the single dispatch point from host OS to platform variants; the per-variant
 * configuration itself lives in [MpvTargets.kt](MpvBuildTarget).
 */
private fun MpvBuildContext.enabledTargets(): List<MpvBuildTarget> = buildList {
    fun skip(family: String) = project.logger.lifecycle(
        "Skipping mpv $family targets: ${buildProperties.buildVariantPropertyName} does not include '$family'.",
    )

    when (hostOs) {
        Os.Windows -> if (isBuildVariantEnabled("windows")) add(windowsTarget()) else skip("windows")
        Os.Linux -> if (isBuildVariantEnabled("linux")) add(linuxX64Target()) else skip("linux")
        Os.MacOS -> if (isBuildVariantEnabled("macos")) {
            when (hostArch) {
                Arch.AARCH64 -> add(macosArm64Target())
                Arch.X86_64 -> add(macosX64Target())
                Arch.UNKNOWN -> error("Failed to configure mpv tasks, unknown macOS host architecture.")
            }
        } else {
            skip("macos")
        }

        Os.Unknown -> project.logger.warn("Unsupported host OS for mpv build tasks: $hostOs.")
    }

    if (hostOs != Os.Unknown) {
        if (!isBuildVariantEnabled("android")) {
            skip("android")
        } else if (runCatching { project.resolveNdkDir() }.isFailure) {
            project.logger.warn(
                "Android NDK not found – skipping Android mpv targets. Set ndk.dir or ANDROID_NDK_HOME to enable.",
            )
        } else {
            androidAbis.forEach { abi -> add(androidTarget(abi)) }
        }
    }
}

private fun registerMpvTasks(
    context: MpvBuildContext,
    target: MpvBuildTarget,
    sourceTemplateTask: TaskProvider<PrepareSourceTreeTask>,
    sourceTemplateDirProvider: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    previousTargetTask: TaskProvider<out Task>?,
): TaskProvider<out Task>? {
    val project = context.project
    val buildDir = project.layout.buildDirectory.dir("mpv/${target.name}")
    val outputDirProvider = project.layout.buildDirectory.dir("mpv-output/${target.name}")
    val configStamp = project.layout.buildDirectory.file("mpv/${target.name}/.config_stamp")
    val buildStamp = project.layout.buildDirectory.file("mpv/${target.name}/.build_stamp")
    val jniOutputFile = project.layout.buildDirectory.file("mpv/${target.name}/jni/${target.jni.outputFileName}")
    val ffmpegInstallDir = context.ffmpegInstallDir(target.ffmpegTargetName)
    val ffmpegAssembleTaskName = context.ffmpegAssembleTaskName(target.ffmpegTargetName)
    if (!context.ffmpegProject.tasks.names.contains(ffmpegAssembleTaskName)) {
        project.logger.lifecycle(
            "Skipping mpv ${target.name}: mediamp-ffmpeg task '$ffmpegAssembleTaskName' is unavailable. " +
                "Enable the matching ${context.ffmpegBuildProperties.buildVariantPropertyName} family '${target.family}'.",
        )
        return previousTargetTask
    }
    val msys2Dir = if (context.hostOs == Os.Windows) context.project.resolveMsys2Dir() else null
    val toolchainFingerprint = project.toolchainFingerprint(target.toolchainProbes)
    val jniToolchainFingerprint = project.toolchainFingerprint(target.jni.versionProbes)

    val configureTask = project.tasks.register<MpvConfigureTask>("mpvConfigure${target.name}") {
        group = "mpv"
        description = "Run Meson configure for mpv ${target.name}"
        dependsOn(sourceTemplateTask)
        dependsOn(context.ffmpegProject.tasks.named(ffmpegAssembleTaskName))
        this.sourceTemplateDir.set(sourceTemplateDirProvider)
        this.ffmpegInstallDir.set(ffmpegInstallDir)
        mesonBuildType.set(context.mesonBuildType)
        setupArgs.set(target.mesonOptions)
        shell.set(target.shell)
        envVars.set(target.env)
        hostOsName.set(context.hostOs.name)
        wrapDependencies.set(target.wrapDependencies)
        wrapFiles.set(target.wrapFiles)
        msys2Packages.set(target.msys2Packages)
        buildDirPath.set(buildDir)
        stagedSourceDir.set(buildDir.map { it.dir("source") })
        this.configStamp.set(configStamp)
        target.androidAbi?.let { crossFileContent.set(context.androidCrossFileContent(it)) }
        if (msys2Dir != null) {
            this.msys2Dir.set(msys2Dir)
        }
    }

    val buildTask = project.tasks.register<MpvBuildTask>("mpvBuild${target.name}") {
        group = "mpv"
        description = "Build mpv for ${target.name}"
        dependsOn(configureTask)
        stagedSourceDir.set(configureTask.flatMap { it.stagedSourceDir })
        this.ffmpegInstallDir.set(ffmpegInstallDir)
        this.configStamp.set(configStamp)
        shell.set(target.shell)
        envVars.set(target.env)
        hostOsName.set(context.hostOs.name)
        this.toolchainFingerprint.set(toolchainFingerprint)
        target.androidAbi?.let { abi ->
            crossFileFingerprint.set(
                sanitizeAbsolutePaths(
                    context.androidCrossFileContent(abi),
                    listOf(project.rootProject.projectDir),
                ),
            )
        }
        buildDirPath.set(buildDir)
        installDir.set(buildDir.map { it.dir("install") })
        this.buildStamp.set(buildStamp)
        if (target.androidAbi != null) {
            // Cross builds configure prefix=/ and install via --destdir (see MpvConfigureTask).
            installDestDir.set(buildDir.map { it.dir("install").asFile.absolutePath })
        }
    }

    val jniTask = project.tasks.register<MpvJniBuildTask>("mpvBuildJni${target.name}") {
        group = "mpv"
        description = "Build the mediamp JNI wrapper for ${target.name}"
        dependsOn(buildTask)
        targetName.set(target.name)
        sourceDir.set(project.layout.projectDirectory.dir("src/cpp"))
        mpvInstallDir.set(buildTask.flatMap { it.installDir })
        this.ffmpegInstallDir.set(ffmpegInstallDir)
        shell.set(target.shell)
        envVars.set(target.env)
        hostOsName.set(context.hostOs.name)
        this.toolchainFingerprint.set(jniToolchainFingerprint)
        jdkMajorVersion.set(JavaVersion.current().majorVersion)
        compilerCommand.set(target.jni.compilerCommand)
        compilerArgs.set(target.jni.compilerArgs)
        linkerArgs.set(target.jni.linkerArgs)
        sourceExtensions.set(target.jni.sourceExtensions)
        useJdkIncludes.set(target.jni.useJdkIncludes)
        linkLibraryPatterns.set(target.jni.linkLibraryPatterns)
        outputFile.set(jniOutputFile)
        if (msys2Dir != null) {
            this.msys2Dir.set(msys2Dir)
        }
    }

    val assembleTask = project.tasks.register<MpvAssembleTask>("mpvAssemble${target.name}") {
        group = "mpv"
        description = "Assemble mpv outputs for ${target.name}"
        dependsOn(jniTask)
        if (previousTargetTask != null) {
            mustRunAfter(previousTargetTask)
        }
        targetName.set(target.name)
        installDir.set(buildTask.flatMap { it.installDir })
        this.ffmpegInstallDir.set(ffmpegInstallDir)
        jniLibrary.set(jniOutputFile)
        runtimeDirName.set(target.runtime.runtimeDirName)
        postProcessing.set(target.runtime.postProcessing.name)
        this.outputDir.set(outputDirProvider)
        if (msys2Dir != null) {
            this.msys2Dir.set(msys2Dir)
        }
        target.androidAbi?.let { abi ->
            androidLibcxxShared.set(context.androidToolchain(abi).libcxxShared)
        }
    }

    return assembleTask
}
