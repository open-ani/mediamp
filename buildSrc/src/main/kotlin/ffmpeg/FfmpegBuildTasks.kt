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
import org.gradle.api.Task
import org.gradle.kotlin.dsl.register
import java.io.ByteArrayOutputStream
import java.io.File

internal fun registerHostFfmpegTasks(context: FfmpegBuildContext) {
    with(context) {
        when (hostOs) {
            Os.Windows -> {
                if (isBuildVariantEnabled("windows")) {
                    registerFfmpegTasks(context, windowsTarget())
                } else {
                    project.logger.lifecycle("Skipping FFmpeg windows targets: mediamp.ffmpeg.buildvariant does not include 'windows'.")
                }
                registerAndroidTargetsIfAvailable(context)
            }

            Os.Linux -> {
                if (isBuildVariantEnabled("linux")) {
                    registerFfmpegTasks(context, linuxX64Target)
                } else {
                    project.logger.lifecycle("Skipping FFmpeg linux targets: mediamp.ffmpeg.buildvariant does not include 'linux'.")
                }
                registerAndroidTargetsIfAvailable(context)
            }

            Os.MacOS -> {
                if (isBuildVariantEnabled("macos")) {
                    when (hostArch) {
                        Arch.AARCH64 -> registerFfmpegTasks(context, macosArm64Target)
                        Arch.X86_64 -> registerFfmpegTasks(context, macosX64Target)
                    }
                } else {
                    project.logger.lifecycle("Skipping FFmpeg macos targets: mediamp.ffmpeg.buildvariant does not include 'macos'.")
                }
                if (isBuildVariantEnabled("ios")) {
                    registerFfmpegTasks(context, iosArm64Target)
                    registerFfmpegTasks(context, iosSimulatorArm64Target)
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
    with(context) {
        val buildDir = project.layout.buildDirectory.dir("ffmpeg/${target.name}").get().asFile
        val installDir = buildDir.resolve("install")
        val targetSourceDir = project.layout.buildDirectory.dir("ffmpeg-source/${target.name}").get().asFile
        val configStamp = buildDir.resolve(".config_stamp")
        val buildStamp = buildDir.resolve(".build_stamp")
        val targetSourceTask = project.tasks.register("prepareFfmpegSource${target.name}") {
            group = "ffmpeg"
            description = "Copy FFmpeg source tree for ${target.name}"
            inputs.dir(ffmpegSrcDir)
            outputs.dir(targetSourceDir)
            doFirst {
                require(ffmpegSrcDir.resolve("configure").isFile) {
                    "FFmpeg source tree is missing configure at ${ffmpegSrcDir.absolutePath}"
                }
            }
            doLast {
                targetSourceDir.deleteRecursively()
                ffmpegSrcDir.copyRecursively(targetSourceDir, overwrite = true)
                restoreExecutablePermissions(ffmpegSrcDir, targetSourceDir)
            }
        }

        val configureTask = project.tasks.register("ffmpegConfigure${target.name}") {
            group = "ffmpeg"
            description = "Run FFmpeg configure for ${target.name}"
            dependsOn(targetSourceTask)
            inputs.files(targetSourceTask)
            inputs.property("configureFlags", commonConfigureFlags + target.extraFlags)
            outputs.file(configStamp)

            doLast {
                buildDir.deleteRecursively()
                buildDir.mkdirs()

                if (hostOs == Os.Windows) {
                    val packages = listOf(
                        "make",
                        "diffutils",
                        "pkg-config",
                        "mingw-w64-ucrt-x86_64-gcc",
                        "mingw-w64-ucrt-x86_64-nasm",
                    )
                    project.logger.lifecycle("Ensuring MSYS2 UCRT64 packages: ${packages.joinToString()}")
                    execHelper.execOps.exec {
                        commandLine(
                            target.shell,
                            "-l",
                            "-c",
                            "pacman -S --needed --noconfirm ${packages.joinToString(" ")}",
                        )
                        environment(target.env)
                    }
                }

                val prefixPath =
                    if (hostOs == Os.Windows) installDir.absolutePath.toMsysPath() else installDir.absolutePath
                val allFlags = buildList {
                    add("--prefix=$prefixPath")
                    addAll(commonConfigureFlags)
                    addAll(target.extraFlags)
                }
                val configurePath = if (hostOs == Os.Windows) {
                    targetSourceDir.resolve("configure").absolutePath.toMsysPath()
                } else {
                    targetSourceDir.resolve("configure").absolutePath
                }
                val buildDirPath =
                    if (hostOs == Os.Windows) buildDir.absolutePath.toMsysPath() else buildDir.absolutePath
                val flagsStr = allFlags.joinToString(" ") { flag -> if (' ' in flag) "'$flag'" else flag }

                execHelper.execOps.exec {
                    commandLine(target.shell, "-l", "-c", "cd '$buildDirPath' && '$configurePath' $flagsStr")
                    environment(target.env)
                }

                configStamp.writeText(allFlags.joinToString("\n"))
            }
        }

        val buildTask = project.tasks.register("ffmpegBuild${target.name}") {
            group = "ffmpeg"
            description = "Build FFmpeg for ${target.name}"
            dependsOn(configureTask)
            inputs.file(configStamp)
            inputs.files(targetSourceTask)
            outputs.file(buildStamp)

            doLast {
                val buildDirPath =
                    if (hostOs == Os.Windows) buildDir.absolutePath.toMsysPath() else buildDir.absolutePath
                execHelper.execOps.exec {
                    commandLine(target.shell, "-l", "-c", "cd '$buildDirPath' && make -j$makeJobs && make install")
                    environment(target.env)
                }
                buildStamp.writeText(System.currentTimeMillis().toString())
            }
        }

        project.tasks.register("ffmpegAssemble${target.name}") {
            group = "ffmpeg"
            description = "Assemble FFmpeg outputs for ${target.name}"
            dependsOn(buildTask)

            val outputDir = project.layout.buildDirectory.dir("ffmpeg-output/${target.name}").get().asFile
            outputs.dir(outputDir)

            doLast {
                outputDir.mkdirs()
                val libDir = installDir.resolve("lib")
                val binDir = installDir.resolve("bin")
                val candidates = (libDir.listFiles() ?: emptyArray()) + (binDir.listFiles() ?: emptyArray())

                ffmpegLibNames.forEach { libName ->
                    candidates.filter { file ->
                        file.name.matches(Regex("${target.libPrefix}${libName}[.-].*\\.${target.libExtension}.*")) ||
                                file.name == "${target.libPrefix}${libName}.${target.libExtension}"
                    }.forEach { src ->
                        src.copyTo(outputDir.resolve(src.name), overwrite = true)
                    }
                }

                val ffmpegExe =
                    if (target.libExtension == "dll") binDir.resolve("ffmpeg.exe") else binDir.resolve("ffmpeg")
                if (ffmpegExe.exists()) {
                    ffmpegExe.copyTo(outputDir.resolve(ffmpegExe.name), overwrite = true)
                }

                if (target.name == "IosArm64" || target.name == "IosSimulatorArm64") {
                    buildAppleWrapper(context, target, buildDir, installDir, outputDir)
                    rewriteAppleInstallNames(context, target, installDir, outputDir)
                }

                if (hostOs == Os.Windows) {
                    collectWindowsRuntimeDlls(context, outputDir)
                }

                val includeDir = installDir.resolve("include")
                if (includeDir.isDirectory) {
                    includeDir.copyRecursively(outputDir.resolve("include"), overwrite = true)
                }

                project.logger.lifecycle("FFmpeg ${target.name} outputs assembled in: $outputDir")
            }
        }
    }
}

private fun restoreExecutablePermissions(sourceDir: File, targetDir: File) {
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

private fun buildAppleWrapper(
    context: FfmpegBuildContext,
    target: FfmpegBuildTarget,
    buildDir: File,
    installDir: File,
    outputDir: File,
) {
    require(context.wrapperSource.isFile) {
        "FFmpeg wrapper source not found at ${context.wrapperSource.absolutePath}"
    }

    val wrapperName = "libffmpegkitcmd.dylib"
    val sdk = if (target.name == "IosSimulatorArm64") "iphonesimulator" else "iphoneos"
    val minVersionFlag = if (target.name == "IosSimulatorArm64") {
        "-miphonesimulator-version-min=16.0"
    } else {
        "-miphoneos-version-min=16.0"
    }
    val fftoolsDir = buildDir.resolve("fftools")
    val fftoolsObjects = fftoolsDir.walkTopDown()
        .filter { it.isFile && it.extension == "o" }
        .map(File::getAbsolutePath)
        .toList()
    require(fftoolsObjects.isNotEmpty()) {
        "No fftools object files found in $fftoolsDir while building $wrapperName."
    }

    val wrapperOut = buildDir.resolve(wrapperName)
    val linkCmd = mutableListOf(
        "xcrun", "-sdk", sdk, "clang",
        "-dynamiclib",
        "-arch", "arm64",
        minVersionFlag,
        "-fembed-bitcode",
        "-Wl,-install_name,@rpath/$wrapperName",
        "-o", wrapperOut.absolutePath,
        context.wrapperSource.absolutePath,
    )
    linkCmd.addAll(fftoolsObjects)
    linkCmd.addAll(
        listOf(
            "-L${buildDir.resolve("libavdevice").absolutePath}",
            "-L${buildDir.resolve("libavfilter").absolutePath}",
            "-L${buildDir.resolve("libavformat").absolutePath}",
            "-L${buildDir.resolve("libavcodec").absolutePath}",
            "-L${buildDir.resolve("libswresample").absolutePath}",
            "-L${buildDir.resolve("libswscale").absolutePath}",
            "-L${buildDir.resolve("libavutil").absolutePath}",
            "-lavdevice",
            "-lavfilter",
            "-lavformat",
            "-lavcodec",
            "-lswresample",
            "-lswscale",
            "-lavutil",
            "-lm",
            "-pthread",
            "-framework", "CoreFoundation",
            "-framework", "CoreVideo",
            "-framework", "CoreMedia",
        ),
    )

    context.execHelper.execOps.exec {
        commandLine(linkCmd)
        workingDir = buildDir
    }

    wrapperOut.copyTo(outputDir.resolve(wrapperName), overwrite = true)
}

private fun rewriteAppleInstallNames(
    context: FfmpegBuildContext,
    target: FfmpegBuildTarget,
    installDir: File,
    outputDir: File,
) {
    if (target.name != "IosArm64" && target.name != "IosSimulatorArm64") return

    val copiedDylibs = outputDir.listFiles()?.filter { it.isFile && it.name.endsWith(".dylib") }.orEmpty()
    val machOFiles = buildList {
        addAll(copiedDylibs)
        val ffmpegBinary = outputDir.resolve("ffmpeg")
        if (ffmpegBinary.exists()) add(ffmpegBinary)
    }
    val installNameMap = copiedDylibs.associateBy(
        keySelector = { installDir.resolve("lib/${it.name}").absolutePath },
        valueTransform = { "@loader_path/${it.name}" },
    )

    copiedDylibs.forEach { dylib ->
        context.execHelper.execOps.exec {
            commandLine("xcrun", "install_name_tool", "-id", "@loader_path/${dylib.name}", dylib.absolutePath)
        }
    }

    machOFiles.forEach { machO ->
        installNameMap.forEach { (oldPath, newPath) ->
            context.execHelper.execOps.exec {
                commandLine("xcrun", "install_name_tool", "-change", oldPath, newPath, machO.absolutePath)
                isIgnoreExitValue = true
            }
        }
    }
}

private fun collectWindowsRuntimeDlls(context: FfmpegBuildContext, outputDir: File) {
    val ucrt64Bin = context.msys2Dir.resolve("ucrt64/bin")
    val collectedDlls = mutableSetOf<String>()

    fun collectDeps(dllFile: File) {
        if (!dllFile.exists()) return
        val stdout = ByteArrayOutputStream()
        context.execHelper.execOps.exec {
            commandLine(context.msys2Dir.resolve("ucrt64/bin/objdump.exe").absolutePath, "-p", dllFile.absolutePath)
            standardOutput = stdout
            isIgnoreExitValue = true
        }
        stdout.toString(Charsets.UTF_8).lineSequence()
            .filter { it.contains("DLL Name:") }
            .map { it.substringAfter("DLL Name:").trim() }
            .filter { dllName ->
                dllName !in collectedDlls &&
                        !dllName.startsWith("api-ms-win-") &&
                        !dllName.equals("KERNEL32.dll", ignoreCase = true) &&
                        !dllName.equals("USER32.dll", ignoreCase = true) &&
                        !dllName.equals("ADVAPI32.dll", ignoreCase = true) &&
                        !dllName.equals("SHELL32.dll", ignoreCase = true) &&
                        !dllName.equals("ole32.dll", ignoreCase = true) &&
                        !dllName.equals("bcrypt.dll", ignoreCase = true) &&
                        ucrt64Bin.resolve(dllName).exists() &&
                        context.ffmpegLibNames.none { lib -> dllName.startsWith(lib) }
            }
            .forEach { dllName ->
                collectedDlls.add(dllName)
                val src = ucrt64Bin.resolve(dllName)
                src.copyTo(outputDir.resolve(dllName), overwrite = true)
                collectDeps(src)
            }
    }

    outputDir.listFiles()?.filter { it.extension == "dll" }?.forEach(::collectDeps)
    if (collectedDlls.isNotEmpty()) {
        context.project.logger.lifecycle("Collected external DLLs from MSYS2: ${collectedDlls.sorted().joinToString()}")
    }
}
