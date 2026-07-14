package ffmpeg

import nativebuild.copyTreeRecursively
import nativebuild.isWindowsSystemLibrary
import nativebuild.jniIncludeFlags
import nativebuild.makePkgConfigRelocatable
import nativebuild.pathForShell
import nativebuild.parseWindowsImportedDllNames
import nativebuild.recreateDirectory
import nativebuild.resolveWindowsObjdump
import nativebuild.restoreExecutablePermissions
import nativebuild.rewriteMachOToLoaderPath
import nativebuild.sanitizeAbsolutePaths
import nativebuild.shellQuote
import nativebuild.toMsysPath
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Stages the patched source template into the target build directory and runs
 * `./configure`. Deliberately NOT cacheable: its interesting product is the whole staged
 * build tree (hundreds of MB) which is cheap to recreate locally but expensive to store;
 * the cache boundary is [FfmpegBuildTask], whose outputs are the compiled artifacts.
 */
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

    /**
     * Whole target build directory. Internal on purpose: configure only owns
     * [stagedSourceDir] and the generated config files; `make` (the build task) writes
     * its objects and the install tree in here too, and declaring the whole directory as
     * an output would make the two tasks' outputs overlap, which disables build caching.
     */
    @get:Internal
    abstract val buildDirPath: DirectoryProperty

    /** The staged FFmpeg source tree, `<buildDir>/source`. */
    @get:OutputDirectory
    abstract val stagedSourceDir: DirectoryProperty

    /**
     * Absolute install prefix. Declared as an input so that moving/copying a worktree
     * reruns configure: the generated config.mak embeds absolute paths.
     */
    @get:Input
    abstract val installPrefix: Property<String>

    @get:OutputFile
    abstract val configStamp: RegularFileProperty

    /** Tool location, not content input: versions are captured by the toolchain fingerprint. */
    @get:Internal
    abstract val msys2Dir: DirectoryProperty

    @get:Input
    abstract val msys2Packages: ListProperty<String>

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
            missingFfmpegSourceTreeMessage(templateSourceDir)
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
            val packages = msys2Packages.get()
            if (packages.isNotEmpty()) {
                logger.lifecycle("Ensuring MSYS2 packages: ${packages.joinToString()}")
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

        val configureCommand = "cd '$buildDirShellPath' && bash '$configurePath' $flagsStr"

        execOperations.exec {
            commandLine(shell.get(), "-l", "-c", configureCommand)
            environment(envVars.get())
        }

        // The stamp feeds the build task's cache key, so it must not contain the
        // worktree-specific --prefix flag; the remaining flags may reference machine-level
        // tool locations (MSYS2, NDK), which is acceptable.
        configStamp.get().asFile.writeText(configureFlags.get().joinToString("\n"))
    }

    private fun copySourceTree(src: File, dst: File) {
        require(src.resolve("configure").isFile) {
            missingFfmpegSourceTreeMessage(src)
        }
        copyTreeRecursively(src, dst)
        restoreExecutablePermissions(src, dst)
        logger.lifecycle("Prepared FFmpeg source from ${src.absolutePath} to ${dst.absolutePath}")
    }
}

/**
 * Runs `make && make install` — the expensive step and therefore the primary build-cache
 * boundary (layer 1). The cache key is the patched source content, the configure flags,
 * the target environment and the toolchain fingerprint; the cached outputs are the
 * install prefix plus the fftools object files that the assemble task links the JNI
 * wrapper against.
 */
@CacheableTask
abstract class FfmpegBuildTask : DefaultTask() {
    /** Patched source content — the real "what are we building" cache key input. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stagedSourceDir: DirectoryProperty

    /** Configure flags as written by [FfmpegConfigureTask] (without the absolute prefix). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val configStamp: RegularFileProperty

    @get:Input
    abstract val shell: Property<String>

    @get:Input
    abstract val envVars: MapProperty<String, String>

    /** Parallelism is machine-specific and must not affect the cache key. */
    @get:Internal
    abstract val makeJobs: Property<Int>

    @get:Input
    abstract val hostOsName: Property<String>

    /** See [nativebuild.ToolchainFingerprintValueSource]. */
    @get:Input
    abstract val toolchainFingerprint: Property<String>

    /** Working directory prepared by configure; scratch state, not an input or output. */
    @get:Internal
    abstract val buildDirPath: DirectoryProperty

    @get:OutputDirectory
    abstract val installDir: DirectoryProperty

    /**
     * `<buildDir>/fftools` object files. Declared as an output so a cache hit still
     * provides everything [FfmpegAssembleTask] needs to link the JNI wrapper without
     * rerunning make.
     */
    @get:OutputDirectory
    abstract val fftoolsObjectsDir: DirectoryProperty

    @get:OutputFile
    abstract val buildStamp: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        val buildDir = buildDirPath.get().asFile
        val installDirFile = installDir.get().asFile
        require(buildDir.resolve("ffbuild/config.mak").isFile) {
            "FFmpeg build directory at ${buildDir.absolutePath} is not configured. " +
                "Run the matching ffmpegConfigure task first."
        }
        val buildDirShellPath = buildDir.absolutePath
        execOperations.exec {
            commandLine(shell.get(), "-l", "-c", "cd '$buildDirShellPath' && make -j${makeJobs.get()} && make install")
            environment(envVars.get())
        }
        makePkgConfigRelocatable(installDirFile, logger)
        buildStamp.get().asFile.writeText(System.currentTimeMillis().toString())
    }
}

/**
 * Computes a relocatable summary of the toolchain configuration recorded in
 * `ffbuild/config.mak`. Used as a cache key input by the assemble/framework tasks in
 * place of the raw file, which embeds worktree-absolute paths and would defeat
 * cross-worktree cache hits.
 */
internal fun sanitizedFfmpegToolchainSummary(buildDir: File): String {
    val configFile = buildDir.resolve("ffbuild/config.mak")
    if (!configFile.isFile) return "config.mak missing"
    val config = readFfmpegConfig(configFile)
    val summary = listOf("CC", "CPPFLAGS", "CFLAGS", "LDFLAGS", "EXTRALIBS")
        .joinToString("\n") { key -> "$key=${expandMakeVariables(config[key].orEmpty(), config)}" }
    return sanitizeAbsolutePaths(summary, listOf(buildDir))
}

/**
 * Assembles the runtime layout and links the `ffmpegkitjni` wrapper — build-cache
 * layer 2: keyed on the JNI wrapper sources plus the layer-1 outputs, so editing the JNI
 * code only relinks and never invalidates the FFmpeg compile.
 */
@CacheableTask
abstract class FfmpegAssembleTask : DefaultTask() {
    @get:Input
    abstract val targetName: Property<String>

    @get:Input
    abstract val libExtension: Property<String>

    @get:Input
    abstract val libPrefix: Property<String>

    @get:Input
    abstract val ffmpegLibNames: ListProperty<String>

    /**
     * Configure-produced working directory (config.mak, staged source for includes, and
     * the cwd of the wrapper compile). Always present locally because configure runs in
     * every build that needs this task; its cache-relevant content is captured by
     * [toolchainConfigSummary] instead.
     */
    @get:Internal
    abstract val buildDirPath: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val installDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fftoolsObjectsDir: DirectoryProperty

    /** See [sanitizedFfmpegToolchainSummary]. */
    @get:Input
    abstract val toolchainConfigSummary: Property<String>

    @get:Input
    abstract val toolchainFingerprint: Property<String>

    /** The JNI wrapper compiles against the Gradle JVM's JNI headers. */
    @get:Input
    abstract val jdkMajorVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    // Per-platform behavior, provided by FfmpegBuildTarget (FfmpegTargets.kt).

    /** File name of the JVM JNI wrapper to build, absent for targets without one (iOS). */
    @get:Input
    @get:Optional
    abstract val jniWrapperName: Property<String>

    @get:Input
    abstract val jniWrapperLinkFlags: ListProperty<String>

    @get:Input
    abstract val jniWrapperExtraLibs: ListProperty<String>

    @get:Input
    abstract val jniWrapperUseJdkIncludes: Property<Boolean>

    @get:Input
    abstract val msysSubsystem: Property<String>

    @get:Input
    abstract val collectWindowsRuntime: Property<Boolean>

    @get:Input
    abstract val rewriteAppleInstallNames: Property<Boolean>

    @get:Input
    abstract val bundleFfmpegExecutable: Property<Boolean>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    abstract val commandWrapperSource: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    abstract val jniWrapperSource: RegularFileProperty

    /**
     * Tool location for the DLL collection step. Not a content input: the relevant
     * package versions are captured by [toolchainFingerprint].
     */
    @get:Internal
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
        if (ffmpegExe.exists() && bundleFfmpegExecutable.get()) {
            ffmpegExe.copyTo(outputDirFile.resolve(ffmpegExe.name), overwrite = true)
        }

        jniWrapperName.orNull?.let { wrapperName ->
            buildJvmJniWrapper(
                execOperations = execOperations,
                logger = logger,
                wrapperName = wrapperName,
                linkFlags = jniWrapperLinkFlags.get(),
                extraLibs = jniWrapperExtraLibs.get(),
                useJdkIncludes = jniWrapperUseJdkIncludes.get(),
                msysSubsystem = msysSubsystem.get(),
                commandWrapperSource = commandWrapperSource.get().asFile,
                jniWrapperSource = jniWrapperSource.get().asFile,
                buildDir = buildDirFile,
                fftoolsDir = fftoolsObjectsDir.get().asFile,
                installDir = installDirFile,
                outputDir = outputDirFile,
                msys2Dir = msys2Dir.orNull?.asFile,
            )
        }

        if (collectWindowsRuntime.get()) {
            val msys2Root = msys2Dir.orNull?.asFile
                ?: error("MSYS2 directory must be configured for Windows FFmpeg runtime assembly.")
            copyWindowsTlsCertificates(
                logger = logger,
                msys2Dir = msys2Root,
                msysSubsystem = msysSubsystem.get(),
                outputDir = outputDirFile,
            )
            collectWindowsRuntimeDlls(
                execOperations = execOperations,
                logger = logger,
                msys2Dir = msys2Root,
                msysSubsystem = msysSubsystem.get(),
                ffmpegLibNames = ffmpegLibNames.get(),
                outputDir = outputDirFile,
            )
        }

        if (rewriteAppleInstallNames.get()) {
            val bundledDylibs = outputDirFile.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".dylib") }
                .orEmpty()
            val machOFiles = buildList {
                addAll(bundledDylibs)
                val ffmpegBinary = outputDirFile.resolve("ffmpeg")
                if (ffmpegBinary.exists()) add(ffmpegBinary)
            }
            rewriteMachOToLoaderPath(
                execOperations = execOperations,
                machOFiles = machOFiles,
                bundledLibraryNames = bundledDylibs.map(File::getName).toSet(),
            )
        }

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
    msysSubsystem: String,
    outputDir: File,
) {
    val certFile = msys2Dir.resolve("$msysSubsystem/etc/ssl/cert.pem")
    if (!certFile.isFile) return

    val targetFile = outputDir.resolve("etc/ssl/cert.pem")
    targetFile.parentFile.mkdirs()
    certFile.copyTo(targetFile, overwrite = true)
    logger.lifecycle("Bundled Windows TLS CA bundle: ${targetFile.absolutePath}")
}

private fun buildJvmJniWrapper(
    execOperations: ExecOperations,
    logger: Logger,
    wrapperName: String,
    linkFlags: List<String>,
    extraLibs: List<String>,
    useJdkIncludes: Boolean,
    msysSubsystem: String,
    commandWrapperSource: File,
    jniWrapperSource: File,
    buildDir: File,
    fftoolsDir: File,
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
    val wrapperOut = buildDir.resolve(wrapperName)
    val fftoolsObjects = fftoolsDir.walkTopDown()
        .filter { it.isFile && it.extension == "o" }
        .map(File::getAbsolutePath)
        .sorted()
        .toList()
    require(fftoolsObjects.isNotEmpty()) {
        "No fftools object files found in $fftoolsDir while building $wrapperName."
    }

    val windowsMsys = msys2Dir != null
    val shell = if (windowsMsys) {
        msys2Dir?.resolve("usr/bin/bash.exe")?.absolutePath
            ?: error("MSYS2 directory must be configured for FFmpeg JNI wrapper build on Windows.")
    } else {
        "bash"
    }
    val commandWrapper = shellQuote(pathForShell(commandWrapperSource, windowsMsys))
    val jniWrapper = shellQuote(pathForShell(jniWrapperSource, windowsMsys))
    val outputPath = shellQuote(pathForShell(wrapperOut, windowsMsys))
    val buildDirPath = shellQuote(pathForShell(buildDir, windowsMsys))
    val jniIncludes = if (useJdkIncludes) {
        jniIncludeFlags(windowsMsys).joinToString(" ") { shellQuote(it) }
    } else {
        ""
    }
    val ffmpegIncludes = listOf(
        installDir.resolve("include"),
        buildDir.resolve("source"),
    )
        .distinctBy { it.absolutePath }
        .onEach { require(it.isDirectory) { "FFmpeg include directory not found at ${it.absolutePath}" } }
        .joinToString(" ") { "-I${shellQuote(pathForShell(it, windowsMsys))}" }
    val linkerMode = linkFlags.joinToString(" ")
    // Link against the install prefix rather than the make build tree: the install dir is
    // a declared (cacheable) output of the build task, so this also works when the FFmpeg
    // compile was restored from the build cache and the make tree never existed locally.
    val linkLibraries = buildString {
        append("-L${shellQuote(pathForShell(installDir.resolve("lib"), windowsMsys))} ")
        append("-lavdevice -lavfilter -lavformat -lavcodec -lswresample -lswscale -lavutil -lm -pthread")
        extraLibs.forEach { append(" $it") }
    }
    val command = buildString {
        append(expandMakeVariables(config.getValue("CC"), config))
        append(' ')
        append(expandMakeVariables(config["CPPFLAGS"].orEmpty(), config))
        append(' ')
        append(expandMakeVariables(config["CFLAGS"].orEmpty(), config))
        append(' ')
        append(ffmpegIncludes)
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
        append(fftoolsObjects.joinToString(" ") { shellQuote(pathForShell(File(it), windowsMsys)) })
        append(' ')
        append(linkLibraries)
    }

    execOperations.exec {
        commandLine(shell, "-l", "-c", "cd $buildDirPath && $command")
        if (windowsMsys) {
            environment("MSYSTEM", msysSubsystem.uppercase())
        }
    }

    wrapperOut.copyTo(outputDir.resolve(wrapperName), overwrite = true)
    logger.info("Built JVM FFmpeg JNI wrapper: $wrapperOut")
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
    msysSubsystem: String,
    ffmpegLibNames: List<String>,
    outputDir: File,
) {
    val mingwBin = msys2Dir.resolve("$msysSubsystem/bin")
    val objdumpExecutable = resolveWindowsObjdump(msys2Dir, "$msysSubsystem/bin")
    val collectedDlls = mutableSetOf<String>()

    fun collectDeps(dllFile: File) {
        if (!dllFile.exists()) return
        parseWindowsImportedDllNames(execOperations, objdumpExecutable, dllFile).asSequence()
            .filter { dllName ->
                dllName !in collectedDlls &&
                    !isWindowsSystemLibrary(dllName) &&
                    mingwBin.resolve(dllName).exists() &&
                    ffmpegLibNames.none { lib -> dllName.startsWith(lib) }
            }
            .forEach { dllName ->
                collectedDlls.add(dllName)
                val src = mingwBin.resolve(dllName)
                src.copyTo(outputDir.resolve(dllName), overwrite = true)
                collectDeps(src)
            }
    }

    outputDir.listFiles()?.filter { it.extension == "dll" }?.forEach(::collectDeps)
    if (collectedDlls.isNotEmpty()) {
        logger.lifecycle("Collected external DLLs from MSYS2: ${collectedDlls.sorted().joinToString()}")
    }
}
