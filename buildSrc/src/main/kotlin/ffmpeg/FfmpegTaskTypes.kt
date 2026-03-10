package ffmpeg

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
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

    init {
        outputs.upToDateWhen {
            val preparedDir = outputDir.orNull?.asFile
            preparedDir?.isDirectory == true && preparedDir.resolve("configure").isFile
        }
    }

    @TaskAction
    fun run() {
        val src = sourceDir.get().asFile
        val dst = outputDir.get().asFile
        require(src.resolve("configure").isFile) {
            "FFmpeg source tree is missing configure at ${src.absolutePath}"
        }
        dst.deleteRecursively()
        var copiedFiles = 0
        src.walkTopDown().forEach { input ->
            val relative = input.relativeTo(src)
            val output = if (relative.path.isEmpty()) dst else dst.resolve(relative.path)
            if (input.isDirectory) {
                output.mkdirs()
            } else {
                output.parentFile.mkdirs()
                input.copyTo(output, overwrite = true)
                copiedFiles += 1
            }
        }
        restoreExecutablePermissions(src, dst)
        logger.lifecycle("Prepared FFmpeg source from ${src.absolutePath} to ${dst.absolutePath}")
        require(dst.resolve("configure").isFile) {
            "Failed to prepare FFmpeg source: ${dst.resolve("configure").absolutePath} was not copied."
        }
    }
}

abstract class FfmpegConfigureTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceTemplateDir: DirectoryProperty

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
        val templateSourceDir = sourceTemplateDir.get().asFile
        val configureFile = templateSourceDir.resolve("configure")
        require(configureFile.isFile) {
            "FFmpeg source tree is missing configure at ${configureFile.absolutePath}"
        }
        logger.lifecycle("Configuring FFmpeg with buildDir=${buildDir.absolutePath} configure=${configureFile.absolutePath}")
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
            configureFile.absolutePath.toMsysPath()
        } else {
            configureFile.absolutePath
        }
        val buildDirShellPath = if (hostOs == "Windows") buildDir.absolutePath.toMsysPath() else buildDir.absolutePath
        val flagsStr = allFlags.joinToString(" ") { flag -> if (' ' in flag) "'$flag'" else flag }

        val configureCommand = if (hostOs == "Windows") {
            "cd '$buildDirShellPath' && bash '$configurePath' $flagsStr"
        } else {
            "cd '$buildDirShellPath' && bash '$configurePath' $flagsStr"
        }

        execOperations.exec {
            commandLine(shell.get(), "-l", "-c", configureCommand)
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
    abstract val commandWrapperSource: RegularFileProperty

    @get:InputFile
    @get:Optional
    abstract val jniWrapperSource: RegularFileProperty

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
                    wrapperSource = commandWrapperSource.get().asFile,
                    buildDir = buildDirFile,
                    installDir = installDirFile,
                    outputDir = outputDirFile,
                )
            }

            "WindowsX64" -> {
                buildJvmJniWrapper(
                    execOperations = execOperations,
                    logger = logger,
                    targetName = targetName.get(),
                    commandWrapperSource = commandWrapperSource.get().asFile,
                    jniWrapperSource = jniWrapperSource.get().asFile,
                    buildDir = buildDirFile,
                    installDir = installDirFile,
                    outputDir = outputDirFile,
                    msys2Dir = msys2Dir.orNull?.asFile,
                )
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

            "LinuxX64", "MacosArm64", "MacosX64" -> {
                buildJvmJniWrapper(
                    execOperations = execOperations,
                    logger = logger,
                    targetName = targetName.get(),
                    commandWrapperSource = commandWrapperSource.get().asFile,
                    jniWrapperSource = jniWrapperSource.get().asFile,
                    buildDir = buildDirFile,
                    installDir = installDirFile,
                    outputDir = outputDirFile,
                    msys2Dir = null,
                )
            }

            else -> {
                if (targetName.get().startsWith("Android")) {
                    buildJvmJniWrapper(
                        execOperations = execOperations,
                        logger = logger,
                        targetName = targetName.get(),
                        commandWrapperSource = commandWrapperSource.get().asFile,
                        jniWrapperSource = jniWrapperSource.get().asFile,
                        buildDir = buildDirFile,
                        installDir = installDirFile,
                        outputDir = outputDirFile,
                        msys2Dir = null,
                    )
                }
            }
        }

        rewriteAppleInstallNames(
            execOperations = execOperations,
            targetName = targetName.get(),
            installDir = installDirFile,
            outputDir = outputDirFile,
        )

        val includeDir = installDirFile.resolve("include")
        if (includeDir.isDirectory) {
            includeDir.copyRecursively(outputDirFile.resolve("include"), overwrite = true)
        }

        logger.lifecycle("FFmpeg ${targetName.get()} outputs assembled in: $outputDirFile")
    }
}

private fun buildJvmJniWrapper(
    execOperations: ExecOperations,
    logger: Logger,
    targetName: String,
    commandWrapperSource: File,
    jniWrapperSource: File,
    buildDir: File,
    installDir: File,
    outputDir: File,
    msys2Dir: File?,
) {
    require(commandWrapperSource.isFile) {
        "FFmpeg command wrapper source not found at ${commandWrapperSource.absolutePath}"
    }
    require(jniWrapperSource.isFile) {
        "FFmpeg JNI wrapper source not found at ${jniWrapperSource.absolutePath}"
    }

    val config = readFfmpegConfig(buildDir.resolve("ffbuild/config.mak"))
    val wrapperName = when {
        targetName == "WindowsX64" -> "ffmpegkitjni.dll"
        targetName.startsWith("Macos") -> "libffmpegkitjni.dylib"
        else -> "libffmpegkitjni.so"
    }
    val wrapperOut = buildDir.resolve(wrapperName)
    val fftoolsDir = buildDir.resolve("fftools")
    val fftoolsObjects = fftoolsDir.walkTopDown()
        .filter { it.isFile && it.extension == "o" }
        .map(File::getAbsolutePath)
        .toList()
    require(fftoolsObjects.isNotEmpty()) {
        "No fftools object files found in $fftoolsDir while building $wrapperName."
    }

    val shell = if (targetName == "WindowsX64") {
        msys2Dir?.resolve("usr/bin/bash.exe")?.absolutePath
            ?: error("MSYS2 directory must be configured for Windows FFmpeg JNI wrapper build.")
    } else {
        "bash"
    }
    val commandWrapper = shellQuote(pathForShell(commandWrapperSource, targetName == "WindowsX64"))
    val jniWrapper = shellQuote(pathForShell(jniWrapperSource, targetName == "WindowsX64"))
    val outputPath = shellQuote(pathForShell(wrapperOut, targetName == "WindowsX64"))
    val buildDirPath = shellQuote(pathForShell(buildDir, targetName == "WindowsX64"))
    val jniIncludes = jniIncludeFlags(targetName, config).joinToString(" ")
    val linkerMode = when {
        targetName == "WindowsX64" -> "-shared"
        targetName.startsWith("Macos") -> "-dynamiclib -Wl,-install_name,@loader_path/$wrapperName"
        else -> "-shared"
    }
    val linkLibraries = buildString {
        append("-L${shellQuote(pathForShell(buildDir.resolve("libavdevice"), targetName == "WindowsX64"))} ")
        append("-L${shellQuote(pathForShell(buildDir.resolve("libavfilter"), targetName == "WindowsX64"))} ")
        append("-L${shellQuote(pathForShell(buildDir.resolve("libavformat"), targetName == "WindowsX64"))} ")
        append("-L${shellQuote(pathForShell(buildDir.resolve("libavcodec"), targetName == "WindowsX64"))} ")
        append("-L${shellQuote(pathForShell(buildDir.resolve("libswresample"), targetName == "WindowsX64"))} ")
        append("-L${shellQuote(pathForShell(buildDir.resolve("libswscale"), targetName == "WindowsX64"))} ")
        append("-L${shellQuote(pathForShell(buildDir.resolve("libavutil"), targetName == "WindowsX64"))} ")
        append("-lavdevice -lavfilter -lavformat -lavcodec -lswresample -lswscale -lavutil -lm -pthread")
        if (targetName == "WindowsX64") {
            append(" -lstdc++")
        }
    }
    val command = buildString {
        append(expandMakeVariables(config.getValue("CC"), config))
        append(' ')
        append(expandMakeVariables(config["CPPFLAGS"].orEmpty(), config))
        append(' ')
        append(expandMakeVariables(config["CFLAGS"].orEmpty(), config))
        append(' ')
        append(jniIncludes)
        append(' ')
        append(linkerMode)
        append(' ')
        append(expandMakeVariables(config["LDFLAGS"].orEmpty(), config))
        append(" -o ")
        append(outputPath)
        append(' ')
        append(commandWrapper)
        append(' ')
        append(jniWrapper)
        append(' ')
        append(fftoolsObjects.joinToString(" ") { shellQuote(pathForShell(File(it), targetName == "WindowsX64")) })
        append(' ')
        append(linkLibraries)
    }

    execOperations.exec {
        commandLine(shell, "-l", "-c", "cd $buildDirPath && $command")
        if (targetName == "WindowsX64") {
            environment("MSYSTEM", "UCRT64")
        }
    }

    wrapperOut.copyTo(outputDir.resolve(wrapperName), overwrite = true)
    logger.info("Built JVM FFmpeg JNI wrapper: $wrapperOut")
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
    if (targetName != "IosArm64" &&
        targetName != "IosSimulatorArm64" &&
        targetName != "MacosArm64" &&
        targetName != "MacosX64"
    ) return

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

private fun readFfmpegConfig(configFile: File): Map<String, String> {
    require(configFile.isFile) { "FFmpeg config.mak not found at ${configFile.absolutePath}" }
    return configFile.readLines()
        .filter { line -> '=' in line && !line.startsWith('#') }
        .associate { line ->
            val separatorIndex = line.indexOf('=')
            line.substring(0, separatorIndex) to line.substring(separatorIndex + 1)
        }
}

private fun expandMakeVariables(value: String, config: Map<String, String>): String {
    var expanded = value
    val pattern = Regex("""\$\(([^)]+)\)""")
    repeat(8) {
        val replaced = pattern.replace(expanded) { match ->
            config[match.groupValues[1]].orEmpty()
        }
        if (replaced == expanded) return replaced
        expanded = replaced
    }
    return expanded
}

private fun jniIncludeFlags(targetName: String, config: Map<String, String>): List<String> {
    return if (targetName.startsWith("Android")) {
        emptyList()
    } else {
        val javaHome = System.getenv("JAVA_HOME")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(System.getProperty("java.home"))
        val includeDir = javaHome.resolve("include")
        val platformDir = includeDir.resolve(
            when {
                targetName == "WindowsX64" -> "win32"
                targetName.startsWith("Macos") -> "darwin"
                else -> "linux"
            },
        )
        listOf(includeDir, platformDir)
            .onEach { require(it.isDirectory) { "JNI include directory not found at ${it.absolutePath}" } }
            .map { "-I${shellQuote(pathForShell(it, targetName == "WindowsX64"))}" }
    }
}

private fun pathForShell(file: File, windowsMsys: Boolean): String =
    if (windowsMsys) file.absolutePath.toMsysPath() else file.absolutePath

private fun shellQuote(value: String): String =
    "'${value.replace("'", "'\"'\"'")}'"

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
