package mpv

import nativebuild.copyTreePreservingLinks
import nativebuild.deleteRecursivelyForce
import nativebuild.isLinuxSystemLibrary
import nativebuild.isWindowsSystemLibrary
import nativebuild.jniIncludeFlags
import nativebuild.makePkgConfigRelocatable
import nativebuild.pathForShell
import nativebuild.parseWindowsImportedDllNames
import nativebuild.recreateDirectory
import nativebuild.resolveWindowsObjdump
import nativebuild.rewriteMachOToLoaderPath
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.inject.Inject

/**
 * Stages the patched mpv source and runs `meson setup`. Deliberately NOT cacheable: its
 * interesting product is the configured build tree, which is cheap to recreate locally
 * but expensive to store; the cache boundary is [MpvBuildTask].
 */
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
    abstract val msysSubsystem: Property<String>

    @get:Input
    @get:Optional
    abstract val crossFileContent: Property<String>

    /**
     * Whole target build directory. Internal on purpose: configure only owns the staged
     * source; `meson compile`/`meson install` (the build task) write in here too, and
     * declaring the whole directory as an output would overlap with the build task's
     * outputs, which disables build caching.
     */
    @get:Internal
    abstract val buildDirPath: DirectoryProperty

    /** The staged mpv source tree, `<buildDir>/source`, including downloaded wraps. */
    @get:OutputDirectory
    abstract val stagedSourceDir: DirectoryProperty

    @get:OutputFile
    abstract val configStamp: RegularFileProperty

    /** Tool location, not content input: versions are captured by the toolchain fingerprint. */
    @get:Internal
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
        val windowsMsysPrefix = msysSubsystem.orNull ?: "ucrt64"
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
            val certFile = msys2Root.resolve("$windowsMsysPrefix/etc/ssl/cert.pem")
            val certDir = msys2Root.resolve("$windowsMsysPrefix/etc/ssl/certs")
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
                listOf("/$windowsMsysPrefix/lib/pkgconfig", "/$windowsMsysPrefix/share/pkgconfig")
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

        // The stamp feeds the build task's cache key, so it must not contain absolute
        // worktree paths (the FFmpeg install participates in the key as a content input
        // on the build task instead).
        configStamp.get().asFile.writeText(
            buildString {
                appendLine("buildType=${mesonBuildType.get()}")
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

/**
 * Runs `meson compile && meson install` — the expensive step and therefore the primary
 * build-cache boundary (layer 1) for mpv. The cache key is the staged (patched, wraps
 * included) source content, the meson configuration, the FFmpeg install it links
 * against, and the toolchain fingerprint; the cached output is the install prefix.
 */
@CacheableTask
abstract class MpvBuildTask : DefaultTask() {
    /** Staged source content (including downloaded wrap subprojects). */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stagedSourceDir: DirectoryProperty

    /** The FFmpeg install this mpv build compiles and links against. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ffmpegInstallDir: DirectoryProperty

    /** Meson build type and setup args as written by [MpvConfigureTask]. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val configStamp: RegularFileProperty

    /** Cross-file content with worktree-specific paths masked (Android targets). */
    @get:Input
    @get:Optional
    abstract val crossFileFingerprint: Property<String>

    @get:Input
    abstract val shell: Property<String>

    @get:Input
    abstract val envVars: MapProperty<String, String>

    @get:Input
    abstract val hostOsName: Property<String>

    /** See [nativebuild.ToolchainFingerprintValueSource]. */
    @get:Input
    abstract val toolchainFingerprint: Property<String>

    /** Set on cross builds, which configure prefix=/ and install here via --destdir. */
    @get:Internal
    abstract val installDestDir: Property<String>

    /** Working directory prepared by configure; scratch state, not an input or output. */
    @get:Internal
    abstract val buildDirPath: DirectoryProperty

    @get:OutputDirectory
    abstract val installDir: DirectoryProperty

    @get:OutputFile
    abstract val buildStamp: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        val buildRoot = buildDirPath.get().asFile
        val mesonBuildDir = buildRoot.resolve("meson")
        val windowsMsys = hostOsName.get() == "Windows"
        require(mesonBuildDir.isDirectory) {
            "mpv build directory at ${mesonBuildDir.absolutePath} is not configured. " +
                "Run the matching mpvConfigure task first."
        }

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

        makePkgConfigRelocatable(installDir.get().asFile, logger)
        buildStamp.get().asFile.writeText(System.currentTimeMillis().toString())
    }
}

/**
 * Compiles the `mediampv` JNI wrapper — build-cache layer 2: keyed on the wrapper
 * sources plus the layer-1 install outputs, so editing the JNI code only recompiles the
 * wrapper and never invalidates the mpv/FFmpeg compiles.
 */
@CacheableTask
abstract class MpvJniBuildTask : DefaultTask() {
    @get:Input
    abstract val targetName: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mpvInstallDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ffmpegInstallDir: DirectoryProperty

    @get:Input
    abstract val shell: Property<String>

    @get:Input
    abstract val envVars: MapProperty<String, String>

    @get:Input
    abstract val hostOsName: Property<String>

    /** See [nativebuild.ToolchainFingerprintValueSource]. */
    @get:Input
    abstract val toolchainFingerprint: Property<String>

    /** The wrapper may compile against the Gradle JVM's JNI headers. */
    @get:Input
    abstract val jdkMajorVersion: Property<String>

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

    /** Tool location, not content input: versions are captured by [toolchainFingerprint]. */
    @get:Internal
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

    /** Tool location, not content input. */
    @get:Internal
    abstract val msys2Dir: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val msysSubsystem: Property<String>

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
                collectWindowsRuntimeDlls(
                    execOperations,
                    logger,
                    msys2Root,
                    msysSubsystem.orNull ?: "ucrt64",
                    runtimeDir,
                )
            }

            MpvRuntimePostProcessing.MACOS_BUNDLE_DYLIBS -> {
                // Name-based rewrite works for cache-restored install dirs too, whose
                // recorded install names belong to another worktree/machine.
                val bundledDylibs = runtimeDir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".dylib") }
                    .map(File::getCanonicalFile)
                    .distinctBy(File::getAbsolutePath)
                    .toList()
                rewriteMachOToLoaderPath(
                    execOperations = execOperations,
                    machOFiles = bundledDylibs,
                    bundledLibraryNames = runtimeDir.walkTopDown()
                        .filter { it.isFile && it.name.endsWith(".dylib") }
                        .map(File::getName)
                        .toSet(),
                )
                bundleAppleExternalDependencies(
                    execOperations = execOperations,
                    logger = logger,
                    libDir = runtimeDir,
                )
            }

            MpvRuntimePostProcessing.LINUX_RUNPATH_ORIGIN -> {
                bundleLinuxExternalDependencies(
                    execOperations = execOperations,
                    logger = logger,
                    libDir = runtimeDir,
                )
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

// Matches the language-independent parts of a `readelf -d` NEEDED line: the entry type
// and the bracketed soname. The descriptive text between them ("Shared library") is a
// translated string in binutils and must not be relied on; `readelf` is additionally
// invoked with LC_ALL=C.
private val ELF_NEEDED_ENTRY = Regex("""\(NEEDED\)[^\[]*\[([^]]+)]""")

// ldconfig -p line: soname, parenthesized ABI info, resolved path. The ABI info is kept
// so same-soname candidates of different architectures are not conflated.
private val LDCONFIG_ENTRY = Regex("""^(\S+)\s+\(([^)]*)\)\s+=>\s+(/\S+)$""")

/**
 * Makes the Linux runtime self-contained: recursively copies every non-baseline shared
 * library dependency (libplacebo, libass and their transitive deps) into [libDir],
 * mirroring what [bundleAppleExternalDependencies] does for macOS and
 * [collectWindowsRuntimeDlls] does for Windows.
 *
 * Only DIRECT `DT_NEEDED` entries of bundled libraries are considered (via `readelf -d`
 * with `LC_ALL=C`), so dependencies of system-baseline libraries (e.g. libpulse's
 * private `libpulsecommon`) never leak into the package. A candidate soname is resolved
 * first against [libDir] itself (already-bundled siblings like libmpv and the ffmpeg
 * libraries), then against the build machine's loader cache (`ldconfig -p`); when the
 * cache lists several candidates for one soname (multi-arch or glibc-hwcaps variants),
 * only those whose ELF class/machine match the consuming library are eligible, and
 * hardware-capability variants are avoided in favour of the baseline build. The system
 * baseline (glibc, X11, the GL / VAAPI driver stack, audio servers, fontconfig, ...) is
 * skipped per [isLinuxSystemLibrary] and stays resolved via the system linker.
 *
 * Each dependency is copied under the exact soname the loader asks for (e.g.
 * `libplacebo.so.338`), so `RUNPATH=$ORIGIN` (applied afterwards by
 * [setLinuxRunpathOrigin]) picks the bundled copy up regardless of the library versions
 * installed on the target machine. A non-baseline dependency that does not resolve on
 * the build machine fails the build instead of shipping a broken runtime.
 */
private fun bundleLinuxExternalDependencies(
    execOperations: ExecOperations,
    logger: Logger,
    libDir: File,
) {
    if (!libDir.isDirectory) return

    fun listSharedObjects(): List<File> =
        libDir.listFiles()
            ?.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.contains(".so") }
            .orEmpty()

    fun runTool(executable: String, vararg args: String): String {
        val output = ByteArrayOutputStream()
        execOperations.exec {
            this.executable = executable
            this.args(*args)
            // binutils/glibc tools translate parts of their output; pin the C locale so
            // parsing does not depend on the build machine's language settings.
            environment("LC_ALL", "C")
            standardOutput = output
        }
        return output.toString()
    }

    /** Direct `DT_NEEDED` sonames of [library]. */
    fun directDependencies(library: File): List<String> =
        runTool("readelf", "-d", library.absolutePath).lineSequence()
            .mapNotNull { line -> ELF_NEEDED_ENTRY.find(line)?.groupValues?.get(1) }
            .distinct()
            .toList()

    val elfHeaderCache = mutableMapOf<String, String>()

    /** ELF class and machine of [path], e.g. "ELF64 Advanced Micro Devices X86-64". */
    fun elfClassMachine(path: String): String = elfHeaderCache.getOrPut(path) {
        var elfClass = ""
        var machine = ""
        runTool("readelf", "-h", path).lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Class:") -> elfClass = trimmed.substringAfter("Class:").trim()
                trimmed.startsWith("Machine:") -> machine = trimmed.substringAfter("Machine:").trim()
            }
        }
        "$elfClass $machine"
    }

    // soname -> candidate paths on the build machine, from the loader cache. A soname is
    // NOT unique: multi-arch installs and glibc-hwcaps variants coexist under one name.
    val systemLibraryCandidates: Map<String, List<String>> = run {
        val output = ByteArrayOutputStream()
        execOperations.exec {
            executable = "ldconfig"
            args("-p")
            environment("LC_ALL", "C")
            standardOutput = output
        }
        output.toString().lineSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                LDCONFIG_ENTRY.matchEntire(line)
                    ?.let { it.groupValues[1] to it.groupValues[3] }
            }
            .groupBy({ it.first }, { it.second })
    }

    /**
     * Picks the candidate for [soname] whose ELF class/machine matches [consumerHeader],
     * preferring the baseline build over glibc-hwcaps variants (which may require a newer
     * CPU than the runtime targets).
     */
    fun resolveCandidate(soname: String, consumerHeader: String): String? {
        val eligible = systemLibraryCandidates[soname].orEmpty()
            .filter { elfClassMachine(it) == consumerHeader }
        return eligible.firstOrNull { !it.contains("hwcap") } ?: eligible.firstOrNull()
    }

    val bundled = mutableListOf<String>()
    val missing = sortedSetOf<String>()
    val processed = mutableSetOf<String>()
    val pending = ArrayDeque(listSharedObjects())

    while (pending.isNotEmpty()) {
        val library = pending.removeFirst()
        if (!processed.add(library.canonicalPath)) continue
        val consumerHeader = elfClassMachine(library.absolutePath)

        directDependencies(library).forEach { soname ->
            if (isLinuxSystemLibrary(soname)) return@forEach
            if (libDir.listFiles()?.any { it.name == soname } == true) return@forEach
            val path = resolveCandidate(soname, consumerHeader)
            if (path == null) {
                missing += soname
                return@forEach
            }
            val real = File(path).canonicalFile
            require(real.isFile) {
                "Shared library dependency '$soname' of ${library.name} resolved to '$path', which does not exist."
            }
            val target = libDir.resolve(soname)
            real.copyTo(target, overwrite = false)
            bundled += soname
            pending.addLast(target)
        }
    }

    require(missing.isEmpty()) {
        "Linux mpv runtime has unresolved non-system dependencies: ${missing.joinToString()}. " +
                "Install the corresponding development packages (see ci-helper/install-mpv-deps-ubuntu.sh) " +
                "or extend isLinuxSystemLibrary if they belong to the system baseline."
    }

    if (bundled.isNotEmpty()) {
        logger.lifecycle(
            "Bundled Linux external dependencies into ${libDir.name}/: ${bundled.sorted().joinToString()}",
        )
    }
}

/**
 * Makes the bundled Linux libraries load their siblings from the same directory: sets
 * `RUNPATH=$ORIGIN` on every regular `.so`, the ELF equivalent of macOS `@loader_path`
 * and the Windows `SetDllDirectory` path. Without it, `System.load(libmediampv.so)` cannot
 * resolve `libmpv.so.2` and the bundled ffmpeg libraries that sit next to it.
 *
 * Only the system baseline (see [isLinuxSystemLibrary]) is still resolved via the system
 * linker; everything else is bundled by [bundleLinuxExternalDependencies] before this runs.
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
    msysSubsystem: String,
    outputBin: File,
) {
    val msysBin = msys2Dir.resolve("$msysSubsystem/bin")
    val objdumpExecutable = resolveWindowsObjdump(msys2Dir, "$msysSubsystem/bin")
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

                val fromMsys = msysBin.resolve(dllName)
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
