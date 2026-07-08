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
import org.gradle.api.provider.SetProperty
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

        // Meson >= 1.11 validates --prefix against the *host* system's path semantics on
        // cross builds, so a Windows-style prefix is rejected when targeting Android.
        // Cross builds therefore configure prefix=/ and install with --destdir instead
        // (see MpvBuildTask); the assembled layout is identical. MSYS2's argument
        // conversion would rewrite a bare `/` into the msys root's Windows path, so the
        // flag uses the combined form and is excluded from conversion.
        if (crossFile != null && windowsMsys) {
            baseEnv["MSYS2_ARG_CONV_EXCL"] = "--prefix="
        }
        val commandArgs = buildList {
            add("setup")
            add(pathForShell(mesonBuildDir, windowsMsys))
            add(pathForShell(sourceDir, windowsMsys))
            if (crossFile != null) {
                add("--prefix=/")
            } else {
                add("--prefix")
                add(pathForShell(installDir, windowsMsys))
            }
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

    /** Set on cross builds, which configure prefix=/ and install here via --destdir. */
    @get:Input
    @get:Optional
    abstract val installDestDir: Property<String>

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

        val destDirArg = installDestDir.orNull
            ?.let { " --destdir ${shellQuote(pathForShell(File(it), windowsMsys))}" }
            .orEmpty()
        execOperations.exec {
            commandLine(
                shell.get(),
                "-l",
                "-c",
                shellScriptWithExports(
                    envVars.get(),
                    "meson compile -C ${shellQuote(pathForShell(mesonBuildDir, windowsMsys))} && " +
                        "meson install -C ${shellQuote(pathForShell(mesonBuildDir, windowsMsys))}$destDirArg",
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

    // Toolchain description, provided by MpvJniToolchain (MpvTargets.kt).

    @get:Input
    abstract val compilerCommand: Property<String>

    @get:Input
    abstract val compilerArgs: ListProperty<String>

    @get:Input
    abstract val linkerArgs: ListProperty<String>

    @get:Input
    abstract val sourceExtensions: SetProperty<String>

    @get:Input
    abstract val useJdkIncludes: Property<Boolean>

    @get:Input
    abstract val linkLibraryPatterns: ListProperty<String>

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
        val windowsMsys = hostOsName.get() == "Windows"

        val extensions = sourceExtensions.get()
        val sourceFiles = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension in extensions }
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

        val patterns = linkLibraryPatterns.get()
        val mpvLinkLibrary = locateLinkLibrary(mpvInstall.resolve("lib"), patterns, targetName.get(), "mpv")
        val avcodecLinkLibrary = locateLinkLibrary(ffmpegInstall.resolve("lib"), patterns, targetName.get(), "avcodec")

        output.parentFile.mkdirs()

        val args = buildList {
            add(compilerCommand.get())
            addAll(compilerArgs.get())
            if (useJdkIncludes.get()) {
                addAll(jniIncludeFlags(windowsMsys))
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

    // Runtime layout, provided by MpvRuntimeLayout (MpvTargets.kt).

    /** Directory (relative to the install prefix) holding the shared libraries: `bin` or `lib`. */
    @get:Input
    abstract val runtimeDirName: Property<String>

    /** Name of a [MpvRuntimePostProcessing] constant. */
    @get:Input
    abstract val postProcessing: Property<String>

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
        val runtimeSubdir = runtimeDirName.get()
        val runtimeDir = outputPrefix.resolve(runtimeSubdir)

        recreateDirectory(outputPrefix)
        copyTreePreservingLinks(installPrefix, outputPrefix)
        copyTreePreservingLinks(ffmpegPrefix.resolve(runtimeSubdir), runtimeDir)

        jniLibrary.orNull?.asFile?.takeIf(File::isFile)?.let { wrapper ->
            runtimeDir.mkdirs()
            wrapper.copyTo(runtimeDir.resolve(wrapper.name), overwrite = true)
        }

        when (MpvRuntimePostProcessing.valueOf(postProcessing.get())) {
            MpvRuntimePostProcessing.WINDOWS_COLLECT_DLLS -> {
                val msys2Root = msys2Dir.orNull?.asFile
                    ?: error("MSYS2 directory must be configured for Windows mpv assembly.")
                collectWindowsRuntimeDlls(execOperations, logger, msys2Root, runtimeDir)
            }

            MpvRuntimePostProcessing.MACOS_BUNDLE_DYLIBS -> {
                rewriteAppleInstallNames(
                    execOperations = execOperations,
                    mpvInstallDir = installPrefix,
                    ffmpegInstallDir = ffmpegPrefix,
                    outputDir = outputPrefix,
                )
                bundleAppleExternalDependencies(
                    execOperations = execOperations,
                    logger = logger,
                    libDir = runtimeDir,
                )
            }

            MpvRuntimePostProcessing.LINUX_RUNPATH_ORIGIN -> {
                setLinuxRunpathOrigin(
                    execOperations = execOperations,
                    logger = logger,
                    libDir = runtimeDir,
                )
            }

            MpvRuntimePostProcessing.ANDROID_BUNDLE_LIBCXX -> {
                androidLibcxxShared.orNull?.asFile?.takeIf(File::isFile)?.let { libcxx ->
                    runtimeDir.mkdirs()
                    libcxx.copyTo(runtimeDir.resolve(libcxx.name), overwrite = true)
                }
            }
        }
    }
}

/**
 * Makes the bundled Linux libraries load their siblings from the same directory: sets
 * `RUNPATH=$ORIGIN` on every regular `.so`, the ELF equivalent of macOS `@loader_path`
 * and the Windows `SetDllDirectory` path. Without it, `System.load(libmediampv.so)` cannot
 * resolve `libmpv.so.2` and the bundled ffmpeg libraries that sit next to it.
 *
 * System libraries (libass, libplacebo, glibc, X11, ...) are intentionally NOT bundled and
 * are still resolved via the system linker; the Linux runtime is therefore not fully
 * self-contained (documented limitation), but it is loadable wherever those system libraries
 * are present.
 */
private fun setLinuxRunpathOrigin(
    execOperations: ExecOperations,
    logger: Logger,
    libDir: File,
) {
    if (!libDir.isDirectory) return
    val soFiles = libDir.listFiles()
        ?.filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.name.contains(".so") }
        .orEmpty()

    soFiles.forEach { so ->
        execOperations.exec {
            commandLine("patchelf", "--set-rpath", "\$ORIGIN", so.absolutePath)
        }
    }

    if (soFiles.isNotEmpty()) {
        logger.lifecycle("Set RUNPATH=\$ORIGIN on Linux runtime libraries: ${soFiles.map { it.name }.sorted().joinToString()}")
    }
}

/**
 * Makes the macOS runtime self-contained: recursively copies every non-system dylib
 * dependency (e.g. Homebrew libass/libplacebo and their transitive deps) into [libDir]
 * and rewrites the load commands to `@loader_path`, mirroring what
 * [collectWindowsRuntimeDlls] does for Windows. Without this, libmpv keeps absolute
 * `/opt/homebrew/...` references and fails to load on machines without Homebrew.
 */
private fun bundleAppleExternalDependencies(
    execOperations: ExecOperations,
    logger: Logger,
    libDir: File,
) {
    if (!libDir.isDirectory) return

    fun listDylibs(): List<File> =
        libDir.listFiles()?.filter { it.isFile && it.name.endsWith(".dylib") }.orEmpty()

    fun dependencyPaths(machO: File): List<String> {
        val output = java.io.ByteArrayOutputStream()
        execOperations.exec {
            commandLine("xcrun", "otool", "-L", machO.absolutePath)
            standardOutput = output
        }
        return output.toString()
            .lineSequence()
            .drop(1) // first line is the file itself
            .map { it.trim().substringBefore(" (") }
            .filter { it.isNotEmpty() }
            .toList()
    }

    // System libraries and frameworks stay external; anything else must be bundled.
    fun isExternal(path: String): Boolean =
        path.startsWith("/") && !path.startsWith("/usr/lib/") && !path.startsWith("/System/")

    val copied = mutableSetOf<String>()
    var changed = true
    while (changed) {
        changed = false
        listDylibs().forEach { machO ->
            dependencyPaths(machO).forEach inner@{ dep ->
                if (!isExternal(dep)) return@inner
                val real = File(dep).canonicalFile
                require(real.isFile) {
                    "Dylib dependency '$dep' of ${machO.name} does not exist on this machine."
                }
                val bundled = libDir.resolve(real.name)
                if (!bundled.exists()) {
                    real.copyTo(bundled)
                    bundled.setReadable(true, false)
                    bundled.setWritable(true, true) // install_name_tool needs write access
                    bundled.setExecutable(true, false)
                    execOperations.exec {
                        commandLine(
                            "xcrun", "install_name_tool",
                            "-id", "@loader_path/${real.name}",
                            bundled.absolutePath,
                        )
                    }
                    copied.add(real.name)
                    changed = true
                }
                execOperations.exec {
                    commandLine(
                        "xcrun", "install_name_tool",
                        "-change", dep, "@loader_path/${real.name}",
                        machO.absolutePath,
                    )
                    isIgnoreExitValue = true
                }
            }
        }
    }

    // install_name_tool edits invalidate code signatures, and arm64 macOS refuses to load
    // unsigned dylibs — re-sign everything ad-hoc.
    listDylibs().forEach { dylib ->
        execOperations.exec {
            commandLine("codesign", "--force", "--sign", "-", dylib.absolutePath)
        }
    }

    if (copied.isNotEmpty()) {
        logger.lifecycle("Bundled external macOS dylibs for mpv: ${copied.sorted().joinToString()}")
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
    mpvInstallDir: File,
    ffmpegInstallDir: File,
    outputDir: File,
) {
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

/**
 * Resolves a link library by trying each pattern in order. `{name}` is substituted with
 * [baseName]; a pattern ending in `*` matches any file whose name starts with the prefix
 * before the `*` (versioned shared objects like `libmpv.so.2`).
 */
private fun locateLinkLibrary(
    libDir: File,
    patterns: List<String>,
    targetName: String,
    baseName: String,
): File {
    require(libDir.isDirectory) {
        "Library directory not found at ${libDir.absolutePath} while resolving $baseName for $targetName"
    }

    val candidates = patterns.flatMap { pattern ->
        val fileName = pattern.replace("{name}", baseName)
        if (fileName.endsWith("*")) {
            val prefix = fileName.removeSuffix("*")
            libDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(prefix) }
                .orEmpty()
        } else {
            listOf(libDir.resolve(fileName))
        }
    }

    return candidates.firstOrNull(File::isFile)
        ?: error(
            "Failed to locate link library '$baseName' for $targetName under ${libDir.absolutePath}. " +
                "Checked: ${candidates.joinToString { it.name }}",
        )
}
