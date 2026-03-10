package ffmpeg

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

abstract class PrepareFfmpegSourceTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:OutputFile
    abstract val configureFile: RegularFileProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun run() {
        val src = sourceDir.get().asFile
        val dst = outputDir.get().asFile
        require(src.resolve("configure").isFile) {
            "FFmpeg source tree is missing configure at ${src.absolutePath}"
        }
        fileSystemOperations.sync {
            from(src)
            into(dst)
            includeEmptyDirs = true
        }
        restoreExecutablePermissions(src, dst)
        require(configureFile.get().asFile.isFile) {
            "Failed to prepare FFmpeg source: ${configureFile.get().asFile.absolutePath} was not copied."
        }
    }
}

abstract class FfmpegConfigureTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configureFile: RegularFileProperty

    @get:Input
    abstract val configureFlags: ListProperty<String>

    @get:Input
    abstract val shell: Property<String>

    @get:Input
    abstract val envVars: MapProperty<String, String>

    @get:Input
    abstract val hostOsName: Property<String>

    @get:OutputDirectory
    abstract val buildDirPath: DirectoryProperty

    @get:Input
    abstract val installPrefix: Property<String>

    @get:OutputFile
    abstract val configStamp: RegularFileProperty

    @get:InputDirectory
    @get:Optional
    abstract val msys2Dir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        val buildDir = buildDirPath.get().asFile
        val installDir = File(installPrefix.get())
        val hostOs = hostOsName.get()
        buildDir.deleteRecursively()
        buildDir.mkdirs()

        if (hostOs == "Windows") {
            val msys2Root = msys2Dir.orNull?.asFile
                ?: error("MSYS2 directory must be configured for Windows FFmpeg builds.")
            val packages = listOf(
                "make",
                "diffutils",
                "pkg-config",
                "mingw-w64-ucrt-x86_64-gcc",
                "mingw-w64-ucrt-x86_64-nasm",
            )
            logger.lifecycle("Ensuring MSYS2 UCRT64 packages: ${packages.joinToString()}")
            execOperations.exec {
                commandLine(
                    shell.get(),
                    "-l",
                    "-c",
                    "pacman -S --needed --noconfirm ${packages.joinToString(" ")}",
                )
                environment(envVars.get())
                workingDir = msys2Root
            }
        }

        val prefixPath = if (hostOs == "Windows") installDir.absolutePath.toMsysPath() else installDir.absolutePath
        val allFlags = buildList {
            add("--prefix=$prefixPath")
            addAll(configureFlags.get())
        }
        val configurePath = if (hostOs == "Windows") {
            configureFile.get().asFile.absolutePath.toMsysPath()
        } else {
            configureFile.get().asFile.absolutePath
        }
        val buildDirShellPath = if (hostOs == "Windows") buildDir.absolutePath.toMsysPath() else buildDir.absolutePath
        val flagsStr = allFlags.joinToString(" ") { flag -> if (' ' in flag) "'$flag'" else flag }

        execOperations.exec {
            commandLine(shell.get(), "-l", "-c", "cd '$buildDirShellPath' && '$configurePath' $flagsStr")
            environment(envVars.get())
        }

        configStamp.get().asFile.writeText(allFlags.joinToString("\n"))
    }
}

abstract class FfmpegBuildTask : DefaultTask() {
    @get:InputFile
    abstract val configStamp: RegularFileProperty

    @get:Input
    abstract val shell: Property<String>

    @get:Input
    abstract val envVars: MapProperty<String, String>

    @get:Input
    abstract val makeJobs: Property<Int>

    @get:InputDirectory
    abstract val buildDirPath: DirectoryProperty

    @get:OutputFile
    abstract val buildStamp: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        val buildDir = buildDirPath.get().asFile
        val buildDirShellPath = buildDir.absolutePath
        execOperations.exec {
            commandLine(shell.get(), "-l", "-c", "cd '$buildDirShellPath' && make -j${makeJobs.get()} && make install")
            environment(envVars.get())
        }
        buildStamp.get().asFile.writeText(System.currentTimeMillis().toString())
    }
}

abstract class FfmpegAssembleTask : DefaultTask() {
    @get:Input
    abstract val targetName: Property<String>

    @get:Input
    abstract val libExtension: Property<String>

    @get:Input
    abstract val libPrefix: Property<String>

    @get:Input
    abstract val ffmpegLibNames: ListProperty<String>

    @get:InputDirectory
    abstract val buildDirPath: DirectoryProperty

    @get:InputDirectory
    abstract val installDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:InputFile
    @get:Optional
    abstract val wrapperSource: RegularFileProperty

    @get:InputDirectory
    @get:Optional
    abstract val msys2Dir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        val outputDirFile = outputDir.get().asFile
        val installDirFile = installDir.get().asFile
        val buildDirFile = buildDirPath.get().asFile

        outputDirFile.mkdirs()
        val libDir = installDirFile.resolve("lib")
        val binDir = installDirFile.resolve("bin")
        val candidates = (libDir.listFiles() ?: emptyArray()) + (binDir.listFiles() ?: emptyArray())

        ffmpegLibNames.get().forEach { libName ->
            candidates.filter { file ->
                file.name.matches(Regex("${libPrefix.get()}${libName}[.-].*\\.${libExtension.get()}.*")) ||
                    file.name == "${libPrefix.get()}${libName}.${libExtension.get()}"
            }.forEach { src ->
                src.copyTo(outputDirFile.resolve(src.name), overwrite = true)
            }
        }

        val ffmpegExe = if (libExtension.get() == "dll") binDir.resolve("ffmpeg.exe") else binDir.resolve("ffmpeg")
        if (ffmpegExe.exists()) {
            ffmpegExe.copyTo(outputDirFile.resolve(ffmpegExe.name), overwrite = true)
        }

        when (targetName.get()) {
            "IosArm64", "IosSimulatorArm64" -> {
                buildAppleWrapper(
                    execOperations = execOperations,
                    logger = logger,
                    targetName = targetName.get(),
                    wrapperSource = wrapperSource.get().asFile,
                    buildDir = buildDirFile,
                    installDir = installDirFile,
                    outputDir = outputDirFile,
                )
                rewriteAppleInstallNames(
                    execOperations = execOperations,
                    targetName = targetName.get(),
                    installDir = installDirFile,
                    outputDir = outputDirFile,
                )
            }

            "WindowsX64" -> {
                val msys2Root = msys2Dir.orNull?.asFile
                    ?: error("MSYS2 directory must be configured for Windows FFmpeg runtime assembly.")
                collectWindowsRuntimeDlls(
                    execOperations = execOperations,
                    logger = logger,
                    msys2Dir = msys2Root,
                    ffmpegLibNames = ffmpegLibNames.get(),
                    outputDir = outputDirFile,
                )
            }
        }

        val includeDir = installDirFile.resolve("include")
        if (includeDir.isDirectory) {
            includeDir.copyRecursively(outputDirFile.resolve("include"), overwrite = true)
        }

        logger.lifecycle("FFmpeg ${targetName.get()} outputs assembled in: $outputDirFile")
    }
}

private fun buildAppleWrapper(
    execOperations: ExecOperations,
    logger: Logger,
    targetName: String,
    wrapperSource: File,
    buildDir: File,
    installDir: File,
    outputDir: File,
) {
    require(wrapperSource.isFile) {
        "FFmpeg wrapper source not found at ${wrapperSource.absolutePath}"
    }

    val wrapperName = "libffmpegkitcmd.dylib"
    val sdk = if (targetName == "IosSimulatorArm64") "iphonesimulator" else "iphoneos"
    val minVersionFlag = if (targetName == "IosSimulatorArm64") {
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
        wrapperSource.absolutePath,
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

    execOperations.exec {
        commandLine(linkCmd)
        workingDir = buildDir
    }

    wrapperOut.copyTo(outputDir.resolve(wrapperName), overwrite = true)
    logger.info("Built Apple FFmpeg wrapper: $wrapperOut")
}

private fun rewriteAppleInstallNames(
    execOperations: ExecOperations,
    targetName: String,
    installDir: File,
    outputDir: File,
) {
    if (targetName != "IosArm64" && targetName != "IosSimulatorArm64") return

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
        execOperations.exec {
            commandLine("xcrun", "install_name_tool", "-id", "@loader_path/${dylib.name}", dylib.absolutePath)
        }
    }

    machOFiles.forEach { machO ->
        installNameMap.forEach { (oldPath, newPath) ->
            execOperations.exec {
                commandLine("xcrun", "install_name_tool", "-change", oldPath, newPath, machO.absolutePath)
                isIgnoreExitValue = true
            }
        }
    }
}

private fun collectWindowsRuntimeDlls(
    execOperations: ExecOperations,
    logger: Logger,
    msys2Dir: File,
    ffmpegLibNames: List<String>,
    outputDir: File,
) {
    val ucrt64Bin = msys2Dir.resolve("ucrt64/bin")
    val collectedDlls = mutableSetOf<String>()

    fun collectDeps(dllFile: File) {
        if (!dllFile.exists()) return
        val stdout = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(msys2Dir.resolve("ucrt64/bin/objdump.exe").absolutePath, "-p", dllFile.absolutePath)
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
                    ffmpegLibNames.none { lib -> dllName.startsWith(lib) }
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
        logger.lifecycle("Collected external DLLs from MSYS2: ${collectedDlls.sorted().joinToString()}")
    }
}
