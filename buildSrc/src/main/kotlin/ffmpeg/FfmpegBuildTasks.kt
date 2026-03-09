package ffmpeg

import org.gradle.api.Task
import org.gradle.kotlin.dsl.register
import java.io.ByteArrayOutputStream
import java.io.File

internal fun registerHostFfmpegTasks(context: FfmpegBuildContext) {
    with(context) {
        when (hostOs) {
            HostOs.WINDOWS -> {
                if (isBuildVariantEnabled("windows")) {
                    registerFfmpegTasks(context, windowsTarget())
                } else {
                    project.logger.lifecycle("Skipping FFmpeg windows targets: mediamp.ffmpeg.buildvariant does not include 'windows'.")
                }
                registerAndroidTargetsIfAvailable(context)
            }
            HostOs.LINUX -> {
                if (isBuildVariantEnabled("linux")) {
                    registerFfmpegTasks(context, linuxX64Target)
                } else {
                    project.logger.lifecycle("Skipping FFmpeg linux targets: mediamp.ffmpeg.buildvariant does not include 'linux'.")
                }
                registerAndroidTargetsIfAvailable(context)
            }
            HostOs.MACOS -> {
                if (isBuildVariantEnabled("macos")) {
                    registerFfmpegTasks(context, macosArm64Target)
                    registerFfmpegTasks(context, macosX64Target)
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
            HostOs.UNKNOWN -> project.logger.warn("Unknown host OS – no FFmpeg build targets registered.")
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
        "--cross-prefix=${msys2Dir.resolve("ucrt64/bin/x86_64-w64-mingw32-").absolutePath.toMsysPath()}",
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
                targetSourceDir.resolve("configure").setExecutable(true)
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

                if (hostOs == HostOs.WINDOWS) {
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

                val prefixPath = if (hostOs == HostOs.WINDOWS) installDir.absolutePath.toMsysPath() else installDir.absolutePath
                val allFlags = buildList {
                    add("--prefix=$prefixPath")
                    addAll(commonConfigureFlags)
                    addAll(target.extraFlags)
                }
                val configurePath = if (hostOs == HostOs.WINDOWS) {
                    targetSourceDir.resolve("configure").absolutePath.toMsysPath()
                } else {
                    targetSourceDir.resolve("configure").absolutePath
                }
                val buildDirPath = if (hostOs == HostOs.WINDOWS) buildDir.absolutePath.toMsysPath() else buildDir.absolutePath
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
                val buildDirPath = if (hostOs == HostOs.WINDOWS) buildDir.absolutePath.toMsysPath() else buildDir.absolutePath
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

                val ffmpegExe = if (target.libExtension == "dll") binDir.resolve("ffmpeg.exe") else binDir.resolve("ffmpeg")
                if (ffmpegExe.exists()) {
                    ffmpegExe.copyTo(outputDir.resolve(ffmpegExe.name), overwrite = true)
                }

                if (target.name == "IosArm64" || target.name == "IosSimulatorArm64") {
                    buildAppleWrapper(context, target, buildDir, installDir, outputDir)
                    rewriteAppleInstallNames(context, target, installDir, outputDir)
                }

                if (hostOs == HostOs.WINDOWS) {
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
