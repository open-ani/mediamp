package ffmpeg

import nativebuild.copyTreeRecursively
import nativebuild.isWindowsSystemLibrary
import nativebuild.pathForShell
import nativebuild.parseWindowsImportedDllNames
import nativebuild.recreateDirectory
import nativebuild.resolveWindowsObjdump
import nativebuild.restoreExecutablePermissions
import nativebuild.shellQuote
import nativebuild.toMsysPath
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
import java.io.File
import javax.inject.Inject

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
        val sourceDir = buildDir.resolve("source")
        val configureFile = sourceDir.resolve("configure")
        require(templateSourceDir.resolve("configure").isFile) {
            "FFmpeg source tree is missing configure at ${templateSourceDir.absolutePath}"
        }
        logger.lifecycle("Configuring FFmpeg with buildDir=${buildDir.absolutePath} configure=${configureFile.absolutePath}")
        recreateDirectory(buildDir)
        copySourceTree(templateSourceDir, sourceDir)

        require(configureFile.isFile) {
            "Failed to stage FFmpeg source for configure at ${configureFile.absolutePath}"
        }

        if (hostOs == "Windows") {
            val msys2Root = msys2Dir.orNull?.asFile
                ?: error("MSYS2 directory must be configured for Windows FFmpeg builds.")
            val packages = listOf(
                "make",
                "diffutils",
                "pkg-config",
                "mingw-w64-ucrt-x86_64-ca-certificates",
                "mingw-w64-ucrt-x86_64-gcc",
                "mingw-w64-ucrt-x86_64-nasm",
                "mingw-w64-ucrt-x86_64-openssl",
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

    private fun copySourceTree(src: File, dst: File) {
        require(src.resolve("configure").isFile) {
            "FFmpeg source tree is missing configure at ${src.absolutePath}"
        }
        copyTreeRecursively(src, dst)
        restoreExecutablePermissions(src, dst)
        logger.lifecycle("Prepared FFmpeg source from ${src.absolutePath} to ${dst.absolutePath}")
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

    @get:Input
    abstract val hostOsName: Property<String>

    @get:InputDirectory
    abstract val buildDirPath: DirectoryProperty

    @get:Input
    abstract val installDirPath: Property<String>

    @get:OutputFile
    abstract val buildStamp: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        val buildDir = buildDirPath.get().asFile
        val installDirFile = File(installDirPath.get())
        val buildDirShellPath = buildDir.absolutePath
        execOperations.exec {
            commandLine(shell.get(), "-l", "-c", "cd '$buildDirShellPath' && make -j${makeJobs.get()} && make install")
            environment(envVars.get())
        }
        rewritePkgConfigPaths(installDirFile, hostOsName.get(), logger)
        buildStamp.get().asFile.writeText(System.currentTimeMillis().toString())
    }
}

private fun rewritePkgConfigPaths(
    installDir: File,
    hostOs: String,
    logger: Logger,
) {
    if (hostOs != "Windows") return

    val pkgConfigDir = installDir.resolve("lib/pkgconfig")
    if (!pkgConfigDir.isDirectory) return

    val msysPrefix = installDir.absolutePath.toMsysPath()
    val nativePrefix = installDir.absolutePath.replace("\\", "/")
    var rewrittenFiles = 0

    pkgConfigDir.listFiles()
        ?.filter { it.isFile && it.extension == "pc" }
        .orEmpty()
        .forEach { pcFile ->
            val original = pcFile.readText()
            val rewritten = original.replace(msysPrefix, nativePrefix)
            if (rewritten != original) {
                pcFile.writeText(rewritten)
                rewrittenFiles += 1
            }
        }

    if (rewrittenFiles > 0) {
        logger.lifecycle(
            "Rewrote $rewrittenFiles FFmpeg pkg-config file(s) under ${pkgConfigDir.absolutePath} " +
                "to use native Windows paths.",
        )
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

        outputDirFile.deleteRecursively()
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
        if (ffmpegExe.exists() && targetName.get() != "IosArm64" && targetName.get() != "IosSimulatorArm64") {
            ffmpegExe.copyTo(outputDirFile.resolve(ffmpegExe.name), overwrite = true)
        }

        if (targetName.get() == "WindowsX64") {
            val msys2Root = msys2Dir.orNull?.asFile
                ?: error("MSYS2 directory must be configured for Windows FFmpeg runtime assembly.")
            copyWindowsTlsCertificates(
                logger = logger,
                msys2Dir = msys2Root,
                outputDir = outputDirFile,
            )
            collectWindowsRuntimeDlls(
                execOperations = execOperations,
                logger = logger,
                msys2Dir = msys2Root,
                ffmpegLibNames = ffmpegLibNames.get(),
                outputDir = outputDirFile,
            )
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

private fun copyWindowsTlsCertificates(
    logger: Logger,
    msys2Dir: File,
    outputDir: File,
) {
    val certFile = msys2Dir.resolve("ucrt64/etc/ssl/cert.pem")
    if (!certFile.isFile) return

    val targetFile = outputDir.resolve("etc/ssl/cert.pem")
    targetFile.parentFile.mkdirs()
    certFile.copyTo(targetFile, overwrite = true)
    logger.lifecycle("Bundled Windows TLS CA bundle: ${targetFile.absolutePath}")
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

internal fun readFfmpegConfig(configFile: File): Map<String, String> {
    require(configFile.isFile) { "FFmpeg config.mak not found at ${configFile.absolutePath}" }
    return configFile.readLines()
        .filter { line -> '=' in line && !line.startsWith('#') }
        .associate { line ->
            val separatorIndex = line.indexOf('=')
            line.substring(0, separatorIndex) to line.substring(separatorIndex + 1)
        }
}

internal fun expandMakeVariables(value: String, config: Map<String, String>): String {
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

private fun collectWindowsRuntimeDlls(
    execOperations: ExecOperations,
    logger: Logger,
    msys2Dir: File,
    ffmpegLibNames: List<String>,
    outputDir: File,
) {
    val ucrt64Bin = msys2Dir.resolve("ucrt64/bin")
    val objdumpExecutable = resolveWindowsObjdump(msys2Dir)
    val collectedDlls = mutableSetOf<String>()

    fun collectDeps(dllFile: File) {
        if (!dllFile.exists()) return
        parseWindowsImportedDllNames(execOperations, objdumpExecutable, dllFile).asSequence()
            .filter { dllName ->
                dllName !in collectedDlls &&
                    !isWindowsSystemLibrary(dllName) &&
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
