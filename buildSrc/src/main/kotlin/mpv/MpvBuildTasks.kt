package mpv

import Arch
import Os
import ffmpeg.pathForShell
import getPropertyOrNull
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal fun registerHostMpvTasks(context: MpvBuildContext) {
    val project = context.project
    val sourceTemplateDir = project.layout.buildDirectory.dir("mpv-source-template")

    val sourceTemplateTask = project.tasks.register<PrepareMpvSourceTask>("prepareMpvSourceTemplate") {
        group = "mpv"
        description = "Create a stable mpv source snapshot for this build"
        sourceDir.set(context.mpvSrcDir)
        outputDir.set(sourceTemplateDir)
    }

    var previousTargetTask: TaskProvider<out Task>? = null
    when (context.hostOs) {
        Os.Windows -> {
            if (context.isBuildVariantEnabled("windows")) {
                previousTargetTask = registerMpvTasks(context, context.windowsTarget(), sourceTemplateTask, sourceTemplateDir, previousTargetTask)
            } else {
                project.logger.lifecycle("Skipping mpv Windows targets: mediamp.mpv.buildvariant does not include 'windows'.")
            }
            previousTargetTask = registerAndroidTargetsIfAvailable(context, sourceTemplateTask, sourceTemplateDir, previousTargetTask)
        }

        Os.Linux -> {
            if (context.isBuildVariantEnabled("linux")) {
                previousTargetTask = registerMpvTasks(context, context.linuxX64Target, sourceTemplateTask, sourceTemplateDir, previousTargetTask)
            } else {
                project.logger.lifecycle("Skipping mpv Linux targets: mediamp.mpv.buildvariant does not include 'linux'.")
            }
            previousTargetTask = registerAndroidTargetsIfAvailable(context, sourceTemplateTask, sourceTemplateDir, previousTargetTask)
        }

        Os.MacOS -> {
            if (context.isBuildVariantEnabled("macos")) {
                previousTargetTask = when (context.hostArch) {
                    Arch.AARCH64 -> registerMpvTasks(context, context.macosArm64Target, sourceTemplateTask, sourceTemplateDir, previousTargetTask)
                    Arch.X86_64 -> registerMpvTasks(context, context.macosX64Target, sourceTemplateTask, sourceTemplateDir, previousTargetTask)
                    Arch.UNKNOWN -> error("Failed to configure mpv tasks, unknown macOS host architecture.")
                }
            } else {
                project.logger.lifecycle("Skipping mpv macos targets: mediamp.mpv.buildvariant does not include 'macos'.")
            }
            previousTargetTask = registerAndroidTargetsIfAvailable(context, sourceTemplateTask, sourceTemplateDir, previousTargetTask)
        }

        else -> project.logger.warn("Unsupported host OS for mpv build tasks: ${context.hostOs}.")
    }

    project.tasks.register("mpvBuildAll") {
        group = "mpv"
        description = "Build mpv for all targets available on the current host OS"
        dependsOn(project.tasks.matching { it.name.startsWith("mpvAssemble") })
    }
}

private fun registerAndroidTargetsIfAvailable(
    context: MpvBuildContext,
    sourceTemplateTask: TaskProvider<PrepareMpvSourceTask>,
    sourceTemplateDirProvider: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    previousTargetTask: TaskProvider<out Task>?,
): TaskProvider<out Task>? {
    if (!context.isBuildVariantEnabled("android")) {
        context.project.logger.lifecycle("Skipping mpv Android targets: mediamp.mpv.buildvariant does not include 'android'.")
        return previousTargetTask
    }

    val ndkAvailable = runCatching { context.resolveNdkDir() }.isSuccess
    if (!ndkAvailable) {
        context.project.logger.warn("Android NDK not found – skipping Android mpv targets. Set ndk.dir or ANDROID_NDK_HOME to enable.")
        return previousTargetTask
    }

    var lastTask = previousTargetTask
    context.androidAbis.forEach { abi ->
        lastTask = registerMpvTasks(context, context.androidTarget(abi), sourceTemplateTask, sourceTemplateDirProvider, lastTask)
    }
    return lastTask
}

private fun registerMpvTasks(
    context: MpvBuildContext,
    target: MpvBuildTarget,
    sourceTemplateTask: TaskProvider<PrepareMpvSourceTask>,
    sourceTemplateDirProvider: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    previousTargetTask: TaskProvider<out Task>?,
): TaskProvider<out Task>? {
    val project = context.project
    val buildDir = project.layout.buildDirectory.dir("mpv/${target.name}")
    val outputDirProvider = project.layout.buildDirectory.dir("mpv-output/${target.name}")
    val configStamp = project.layout.buildDirectory.file("mpv/${target.name}/.config_stamp")
    val buildStamp = project.layout.buildDirectory.file("mpv/${target.name}/.build_stamp")
    val jniOutputFile = project.layout.buildDirectory.file("mpv/${target.name}/jni/${jniLibraryFileName(target.name)}")
    val ffmpegInstallDir = context.ffmpegInstallDir(target.ffmpegTargetName)
    val ffmpegAssembleTaskName = context.ffmpegAssembleTaskName(target.ffmpegTargetName)
    if (!context.ffmpegProject.tasks.names.contains(ffmpegAssembleTaskName)) {
        project.logger.lifecycle(
            "Skipping mpv ${target.name}: mediamp-ffmpeg task '$ffmpegAssembleTaskName' is unavailable. " +
                "Enable the matching mediamp.ffmpeg.buildvariant family '${target.family}'.",
        )
        return previousTargetTask
    }
    val msys2Dir = if (context.hostOs == Os.Windows) context.resolveMsys2Dir() else null

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
        this.configStamp.set(configStamp)
        shell.set(target.shell)
        envVars.set(target.env)
        hostOsName.set(context.hostOs.name)
        buildDirPath.set(buildDir)
        this.buildStamp.set(buildStamp)
    }

    val jniTask = project.tasks.register<MpvJniBuildTask>("mpvBuildJni${target.name}") {
        group = "mpv"
        description = "Build the mediamp JNI wrapper for ${target.name}"
        dependsOn(buildTask)
        targetName.set(target.name)
        sourceDir.set(project.layout.projectDirectory.dir("src/cpp"))
        mpvInstallDir.set(buildDir.map { it.dir("install") })
        this.ffmpegInstallDir.set(ffmpegInstallDir)
        shell.set(target.shell)
        envVars.set(target.env)
        hostOsName.set(context.hostOs.name)
        compilerCommand.set(context.jniCompilerCommand(target, msys2Dir))
        compilerArgs.set(context.jniCompilerArgs(target))
        linkerArgs.set(context.jniLinkerArgs(target))
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
        installDir.set(buildDir.map { it.dir("install") })
        this.ffmpegInstallDir.set(ffmpegInstallDir)
        jniLibrary.set(jniOutputFile)
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

private fun MpvBuildContext.jniCompilerCommand(target: MpvBuildTarget, msys2Dir: java.io.File?): String {
    return when {
        target.name == "WindowsX64" -> {
            val msysRoot = msys2Dir ?: error("MSYS2 directory must be configured for Windows JNI builds.")
            pathForShell(msysRoot.resolve("ucrt64/bin/g++.exe"), true)
        }

        target.name.startsWith("Macos") ->
            project.getPropertyOrNull("CXX")
                ?: System.getenv("CXX")
                ?: "clang++"

        target.androidAbi != null -> {
            val toolchain = androidToolchain(target.androidAbi)
            pathForShell(toolchain.cxx, hostOs == Os.Windows)
        }

        else -> project.getPropertyOrNull("CXX")
            ?: System.getenv("CXX")
            ?: "g++"
    }
}

private fun MpvBuildContext.jniCompilerArgs(target: MpvBuildTarget): List<String> {
    return when {
        target.androidAbi != null -> {
            val toolchain = androidToolchain(target.androidAbi)
            val windowsMsys = hostOs == Os.Windows
            buildList {
                addAll(toolchain.cxxArgs)
                add("--sysroot=${pathForShell(toolchain.sysroot, windowsMsys)}")
            }
        }

        target.name.startsWith("Macos") -> appleArchArgs(target.name)
        target.name == "LinuxX64" -> listOf("-pthread")
        else -> emptyList()
    }
}

private fun MpvBuildContext.jniLinkerArgs(target: MpvBuildTarget): List<String> {
    return when {
        target.name == "WindowsX64" -> emptyList()
        target.androidAbi != null -> listOf("-landroid", "-llog")
        target.name.startsWith("Macos") -> appleArchArgs(target.name)
        target.name == "LinuxX64" -> listOf("-pthread", "-Wl,-rpath,\$ORIGIN")
        else -> emptyList()
    }
}

private fun jniLibraryFileName(targetName: String): String = when {
    targetName == "WindowsX64" -> "mediampv.dll"
    targetName.startsWith("Macos") -> "libmediampv.dylib"
    else -> "libmediampv.so"
}

private fun appleArchArgs(targetName: String): List<String> = when (targetName) {
    "MacosArm64" -> listOf("-arch", "arm64", "-mmacosx-version-min=12.0")
    "MacosX64" -> listOf("-arch", "x86_64", "-mmacosx-version-min=12.0")
    else -> emptyList()
}
