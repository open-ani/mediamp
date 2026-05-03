package mpv

import nativebuild.copyTreePreservingLinks
import nativebuild.deleteRecursivelyForce
import nativebuild.isWindowsSystemLibrary
import nativebuild.jniIncludeFlags
import nativebuild.pathForShell
import nativebuild.parseWindowsImportedDllNames
import nativebuild.recreateDirectory
import nativebuild.resolveWindowsObjdump
import nativebuild.shellQuote
import nativebuild.shellScriptWithExports
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

abstract class MpvConfigureTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceTemplateDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ffmpegInstallDir: DirectoryProperty

    @get:Input
    abstract val mesonBuildType: Property<String>

    @get:Input
    abstract val setupArgs: ListProperty<String>

    @get:Input
    abstract val shell: Property<String>

    @get:Input
    abstract val envVars: MapProperty<String, String>

    @get:Input
    abstract val hostOsName: Property<String>

    @get:Input
    abstract val wrapDependencies: ListProperty<String>

    @get:Input
    abstract val wrapFiles: MapProperty<String, String>

    @get:Input
    abstract val msys2Packages: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val crossFileContent: Property<String>

    @get:OutputDirectory
    abstract val buildDirPath: DirectoryProperty

    @get:OutputFile
    abstract val configStamp: RegularFileProperty

    @get:InputDirectory
    @get:Optional
    abstract val msys2Dir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        val buildRoot = buildDirPath.get().asFile
        val sourceDir = buildRoot.resolve("source")
        val mesonBuildDir = buildRoot.resolve("meson")
        val installDir = buildRoot.resolve("install")
        val windowsMsys = hostOsName.get() == "Windows"
        val ffmpegInstall = ffmpegInstallDir.get().asFile

        require(ffmpegInstall.isDirectory) {
            "Required FFmpeg install directory not found at ${ffmpegInstall.absolutePath}. " +
                "Build the matching mediamp-ffmpeg target first."
        }

        recreateDirectory(buildRoot)
        copyTreePreservingLinks(sourceTemplateDir.get().asFile, sourceDir)
        writeWrapFiles(sourceDir, wrapFiles.get())

        if (windowsMsys) {
            val msys2Root = msys2Dir.orNull?.asFile
                ?: error("MSYS2 directory must be configured for Windows mpv builds.")
            val packages = msys2Packages.get()
            if (packages.isNotEmpty()) {
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
        }

        installWrapDependencies(sourceDir, windowsMsys)

        val crossFile = crossFileContent.orNull?.takeIf { it.isNotBlank() }?.let {
            buildRoot.resolve("crossfile.txt").also { file -> file.writeText(it) }
        }

        val pkgConfigDir = ffmpegInstall.resolve("lib/pkgconfig")
        val baseEnv = envVars.get().toMutableMap()
        if (windowsMsys) {
            val msys2Root = msys2Dir.orNull?.asFile
                ?: error("MSYS2 directory must be configured for Windows mpv builds.")
            val certFile = msys2Root.resolve("ucrt64/etc/ssl/cert.pem")
            val certDir = msys2Root.resolve("ucrt64/etc/ssl/certs")
            if (certFile.isFile) {
                baseEnv["SSL_CERT_FILE"] = pathForShell(certFile, windowsMsys)
            }
            if (certDir.isDirectory) {
                baseEnv["SSL_CERT_DIR"] = pathForShell(certDir, windowsMsys)
            }
        }
        if (crossFile != null) {
            baseEnv["PKG_CONFIG_LIBDIR"] = pathForShell(pkgConfigDir, windowsMsys)
            baseEnv["PKG_CONFIG_PATH"] = ""
            baseEnv["PKG_CONFIG_SYSTEM_INCLUDE_PATH"] = ""
            baseEnv["PKG_CONFIG_SYSTEM_LIBRARY_PATH"] = ""
            baseEnv["PKG_CONFIG_ALLOW_SYSTEM_CFLAGS"] = ""
            baseEnv["PKG_CONFIG_ALLOW_SYSTEM_LIBS"] = ""
        } else {
            val prefix = pathForShell(pkgConfigDir, windowsMsys)
            val defaultPkgConfigPaths = if (windowsMsys) {
                listOf("/ucrt64/lib/pkgconfig", "/ucrt64/share/pkgconfig")
            } else {
                emptyList()
            }
            val existing = baseEnv["PKG_CONFIG_PATH"]
                ?.split(':', ';')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                .orEmpty()
            baseEnv["PKG_CONFIG_PATH"] = (listOf(prefix) + defaultPkgConfigPaths + existing)
                .distinct()
                .joinToString(":")
        }

        val commandArgs = buildList {
            add("setup")
            add(pathForShell(mesonBuildDir, windowsMsys))
            add(pathForShell(sourceDir, windowsMsys))
            add("--prefix")
            add(pathForShell(installDir, windowsMsys))
            add("--buildtype")
            add(mesonBuildType.get())
            crossFile?.let {
                add("--cross-file")
                add(pathForShell(it, windowsMsys))
            }
            addAll(setupArgs.get())
        }.joinToString(" ") { shellQuote(it) }

        execOperations.exec {
            commandLine(
                shell.get(),
                "-l",
                "-c",
                shellScriptWithExports(
                    baseEnv,
                    "cd ${shellQuote(pathForShell(sourceDir, windowsMsys))} && meson $commandArgs",
                ),
            )
            environment(baseEnv)
        }

        configStamp.get().asFile.writeText(
            buildString {
                appendLine("buildType=${mesonBuildType.get()}")
                appendLine("ffmpegInstall=${ffmpegInstall.absolutePath}")
                append(setupArgs.get().joinToString("\n"))
            },
        )
    }

    private fun installWrapDependencies(sourceDir: File, windowsMsys: Boolean) {
        val wraps = wrapDependencies.get()
        if (wraps.isEmpty()) return

        val wrapdbDir = sourceDir.parentFile.resolve("wrapdb")
        if (!wrapdbDir.resolve(".git").isDirectory) {
            deleteRecursivelyForce(wrapdbDir)
            execOperations.exec {
                commandLine(
                    shell.get(),
                    "-l",
                    "-c",
                    shellScriptWithExports(
                        envVars.get(),
                        "git clone --depth 1 https://github.com/mesonbuild/wrapdb.git " +
                            shellQuote(pathForShell(wrapdbDir, windowsMsys)),
                    ),
                )
                environment(envVars.get())
            }
        }

        wraps.forEach { dependencyName ->
            val wrapFile = wrapdbDir.resolve("subprojects/$dependencyName.wrap")
            require(wrapFile.isFile) {
                "Failed to locate $dependencyName.wrap in ${wrapdbDir.absolutePath}"
            }
            val targetWrap = sourceDir.resolve("subprojects/$dependencyName.wrap")
            targetWrap.parentFile.mkdirs()
            wrapFile.copyTo(targetWrap, overwrite = true)

            val packagefilesDir = wrapdbDir.resolve("subprojects/packagefiles/$dependencyName")
            if (packagefilesDir.isDirectory) {
                val targetPackagefiles = sourceDir.resolve("subprojects/packagefiles/$dependencyName")
                deleteRecursivelyForce(targetPackagefiles)
                copyTreePreservingLinks(packagefilesDir, targetPackagefiles)
            }
        }
    }

    private fun writeWrapFiles(sourceDir: File, files: Map<String, String>) {
        files.forEach { (relativePath, content) ->
            val file = sourceDir.resolve(relativePath)
            file.parentFile.mkdirs()
            file.writeText(content.trim() + System.lineSeparator())
        }
    }
}

abstract class MpvBuildTask : DefaultTask() {
    @get:InputFile
    abstract val configStamp: RegularFileProperty

    @get:Input
    abstract val shell: Property<String>

    @get:Input
    abstract val envVars: MapProperty<String, String>

    @get:Input
    abstract val hostOsName: Property<String>

    @get:OutputDirectory
    abstract val buildDirPath: DirectoryProperty

    @get:OutputFile
    abstract val buildStamp: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        val buildRoot = buildDirPath.get().asFile
        val mesonBuildDir = buildRoot.resolve("meson")
        val windowsMsys = hostOsName.get() == "Windows"

        execOperations.exec {
            commandLine(
                shell.get(),
                "-l",
                "-c",
                shellScriptWithExports(
                    envVars.get(),
                    "meson compile -C ${shellQuote(pathForShell(mesonBuildDir, windowsMsys))} && " +
                        "meson install -C ${shellQuote(pathForShell(mesonBuildDir, windowsMsys))}",
                ),
            )
            environment(envVars.get())
        }

        buildStamp.get().asFile.writeText(System.currentTimeMillis().toString())
    }
}

abstract class MpvJniBuildTask : DefaultTask() {
    @get:Input
    abstract val targetName: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:InputDirectory
    abstract val mpvInstallDir: DirectoryProperty

    @get:InputDirectory
    abstract val ffmpegInstallDir: DirectoryProperty

    @get:Input
    abstract val shell: Property<String>

    @get:Input
    abstract val envVars: MapProperty<String, String>

    @get:Input
    abstract val hostOsName: Property<String>

    @get:Input
    abstract val compilerCommand: Property<String>

    @get:Input
    abstract val compilerArgs: ListProperty<String>

    @get:Input
    abstract val linkerArgs: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:InputDirectory
    @get:Optional
    abstract val msys2Dir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        val sourceRoot = sourceDir.get().asFile
        val mpvInstall = mpvInstallDir.get().asFile
        val ffmpegInstall = ffmpegInstallDir.get().asFile
        val output = outputFile.get().asFile
        val target = targetName.get()
        val windowsMsys = hostOsName.get() == "Windows"

        val sourceFiles = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "cpp" }
            .sortedBy { it.absolutePath }
            .toList()
        require(sourceFiles.isNotEmpty()) {
            "No JNI C++ sources were found under ${sourceRoot.absolutePath}"
        }

        val includeDirs = buildList {
            add(sourceRoot.resolve("include"))
            add(mpvInstall.resolve("include"))
            add(ffmpegInstall.resolve("include"))
        }.onEach { dir ->
            require(dir.isDirectory) { "Required JNI include directory not found at ${dir.absolutePath}" }
        }

        val mpvLinkLibrary = locateLinkLibrary(mpvInstall.resolve("lib"), target, "mpv")
        val avcodecLinkLibrary = locateLinkLibrary(ffmpegInstall.resolve("lib"), target, "avcodec")

        output.parentFile.mkdirs()

        val args = buildList {
            add(compilerCommand.get())
            addAll(compilerArgs.get())
            add("-std=c++17")
            add("-fPIC")
            if (target.startsWith("Macos")) {
                add("-dynamiclib")
            } else {
                add("-shared")
            }
            if (target == "WindowsX64") {
                add("-D_WIN32_WINNT=0x0A00")
            }
            if (!target.startsWith("Android")) {
                addAll(jniIncludeFlags(target, windowsMsys))
            }
            includeDirs.forEach { dir ->
                add("-I${pathForShell(dir, windowsMsys)}")
            }
            add("-o")
            add(pathForShell(output, windowsMsys))
            sourceFiles.forEach { source ->
                add(pathForShell(source, windowsMsys))
            }
            add(pathForShell(mpvLinkLibrary, windowsMsys))
            add(pathForShell(avcodecLinkLibrary, windowsMsys))
            addAll(linkerArgs.get())
        }

        execOperations.exec {
            commandLine(
                shell.get(),
                "-l",
                "-c",
                shellScriptWithExports(
                    envVars.get(),
                    args.joinToString(" ") { shellQuote(it) },
                ),
            )
            environment(envVars.get())
        }
    }
}

abstract class MpvAssembleTask : DefaultTask() {
    @get:Input
    abstract val targetName: Property<String>

    @get:InputDirectory
    abstract val installDir: DirectoryProperty

    @get:InputDirectory
    abstract val ffmpegInstallDir: DirectoryProperty

    @get:InputFile
    @get:Optional
    abstract val jniLibrary: RegularFileProperty

    @get:InputFile
    @get:Optional
    abstract val androidLibcxxShared: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:InputDirectory
    @get:Optional
    abstract val msys2Dir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        val installPrefix = installDir.get().asFile
        val ffmpegPrefix = ffmpegInstallDir.get().asFile
        val outputPrefix = outputDir.get().asFile

        recreateDirectory(outputPrefix)
        copyTreePreservingLinks(installPrefix, outputPrefix)

        jniLibrary.orNull?.asFile?.takeIf(File::isFile)?.let { wrapper ->
            val runtimeDir = when {
                targetName.get() == "WindowsX64" -> outputPrefix.resolve("bin")
                else -> outputPrefix.resolve("lib")
            }
            runtimeDir.mkdirs()
            wrapper.copyTo(runtimeDir.resolve(wrapper.name), overwrite = true)
        }

        when {
            targetName.get() == "WindowsX64" -> {
                copyTreePreservingLinks(ffmpegPrefix.resolve("bin"), outputPrefix.resolve("bin"))
                val msys2Root = msys2Dir.orNull?.asFile
                    ?: error("MSYS2 directory must be configured for Windows mpv assembly.")
                collectWindowsRuntimeDlls(execOperations, logger, msys2Root, outputPrefix.resolve("bin"))
            }

            targetName.get().startsWith("Android") -> {
                copyTreePreservingLinks(ffmpegPrefix.resolve("lib"), outputPrefix.resolve("lib"))
                androidLibcxxShared.orNull?.asFile?.takeIf(File::isFile)?.let { libcxx ->
                    val libDir = outputPrefix.resolve("lib")
                    libDir.mkdirs()
                    libcxx.copyTo(libDir.resolve(libcxx.name), overwrite = true)
                }
            }

            else -> {
                copyTreePreservingLinks(ffmpegPrefix.resolve("lib"), outputPrefix.resolve("lib"))
            }
        }

        rewriteAppleInstallNames(
            execOperations = execOperations,
            targetName = targetName.get(),
            mpvInstallDir = installPrefix,
            ffmpegInstallDir = ffmpegPrefix,
            outputDir = outputPrefix,
        )
    }
}

private fun collectWindowsRuntimeDlls(
    execOperations: ExecOperations,
    logger: Logger,
    msys2Dir: File,
    outputBin: File,
) {
    val ucrt64Bin = msys2Dir.resolve("ucrt64/bin")
    val objdumpExecutable = resolveWindowsObjdump(msys2Dir)
    val copied = mutableSetOf<String>()

    fun shouldIgnore(dllName: String): Boolean = isWindowsSystemLibrary(dllName)

    fun collectDeps(dllFile: File) {
        if (!dllFile.isFile) return

        parseWindowsImportedDllNames(execOperations, objdumpExecutable, dllFile).asSequence()
            .filterNot(::shouldIgnore)
            .forEach { dllName ->
                val existing = outputBin.resolve(dllName)
                if (existing.exists()) {
                    if (copied.add(existing.name.lowercase())) {
                        collectDeps(existing)
                    }
                    return@forEach
                }

                val fromMsys = ucrt64Bin.resolve(dllName)
                if (fromMsys.exists()) {
                    fromMsys.copyTo(existing, overwrite = true)
                    if (copied.add(existing.name.lowercase())) {
                        collectDeps(existing)
                    }
                }
            }
    }

    outputBin.listFiles()?.filter { it.isFile && it.extension.equals("dll", ignoreCase = true) }?.forEach {
        if (copied.add(it.name.lowercase())) {
            collectDeps(it)
        }
    }

    if (copied.isNotEmpty()) {
        logger.lifecycle("Collected Windows runtime DLLs for mpv: ${copied.sorted().joinToString()}")
    }
}

private fun rewriteAppleInstallNames(
    execOperations: ExecOperations,
    targetName: String,
    mpvInstallDir: File,
    ffmpegInstallDir: File,
    outputDir: File,
) {
    if (targetName != "MacosArm64" && targetName != "MacosX64") return

    val outputLibDir = outputDir.resolve("lib")
    val machOFiles = outputLibDir.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".dylib") }
        .map(File::getCanonicalFile)
        .distinctBy(File::getAbsolutePath)
        .toList()

    if (machOFiles.isEmpty()) return

    val installNameMap = buildMap {
        listOf(mpvInstallDir, ffmpegInstallDir)
            .map { it.resolve("lib") }
            .filter(File::isDirectory)
            .forEach { libDir ->
                libDir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".dylib") }
                    .forEach { dylib ->
                        put(dylib.canonicalFile.absolutePath, "@loader_path/${dylib.name}")
                        put(dylib.absolutePath, "@loader_path/${dylib.name}")
                    }
            }
    }

    machOFiles.forEach { dylib ->
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

private fun locateLinkLibrary(
    libDir: File,
    targetName: String,
    baseName: String,
): File {
    require(libDir.isDirectory) {
        "Library directory not found at ${libDir.absolutePath} while resolving $baseName for $targetName"
    }

    val candidates = when {
        targetName == "WindowsX64" -> listOf(
            libDir.resolve("lib$baseName.dll.a"),
            libDir.resolve("$baseName.lib"),
        )

        targetName.startsWith("Macos") -> listOf(
            libDir.resolve("lib$baseName.dylib"),
        )

        else -> listOf(
            libDir.resolve("lib$baseName.so"),
        ) + libDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("lib$baseName.so.") }
            .orEmpty()
    }

    return candidates.firstOrNull(File::isFile)
        ?: error(
            "Failed to locate link library '$baseName' for $targetName under ${libDir.absolutePath}. " +
                "Checked: ${candidates.joinToString { it.name }}",
        )
}
