package nativebuild

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import javax.inject.Inject

internal const val MINIMUM_LINUX_GLIBC_VERSION = "2.39"

internal fun manifestRelativePath(rootDir: File, file: File): String =
    file.relativeTo(rootDir).path.replace("\\", "/")

internal fun isSharedRuntimeLibrary(file: File, os: String): Boolean =
    when (os) {
        "windows" -> file.name.endsWith(".dll", ignoreCase = true)
        "macos" -> file.name.endsWith(".dylib", ignoreCase = true)
        "linux" -> file.name.endsWith(".so", ignoreCase = true) || ".so." in file.name
        else -> false
    }

internal fun sharedLibrarySpecificityComparator(): Comparator<File> =
    compareByDescending<File> { it.name.length }
        .thenBy { it.name.lowercase(Locale.ROOT) }

internal fun orderLibrariesByPrefixes(
    files: Iterable<File>,
    orderedPrefixes: List<String>,
    unmatchedFirst: Boolean,
): List<File> {
    val remainingByPrefix = orderedPrefixes.associateWith { mutableListOf<File>() }.toMutableMap()
    val unmatched = mutableListOf<File>()

    files.forEach { file ->
        val prefix = orderedPrefixes.firstOrNull { file.name.startsWith(it) }
        if (prefix == null) {
            unmatched += file
        } else {
            remainingByPrefix.getValue(prefix) += file
        }
    }

    return buildList {
        val unmatchedSorted = unmatched.sortedWith(sharedLibrarySpecificityComparator())
        if (unmatchedFirst) {
            addAll(unmatchedSorted)
        }
        orderedPrefixes.forEach { prefix ->
            addAll(remainingByPrefix.getValue(prefix).sortedWith(sharedLibrarySpecificityComparator()))
        }
        if (!unmatchedFirst) {
            addAll(unmatchedSorted)
        }
    }
}

internal fun resolveWindowsObjdump(msys2Dir: File, binDirName: String = "ucrt64/bin"): File =
    (msys2Dir.resolve("$binDirName/objdump.exe").takeIf(File::isFile)
        ?: msys2Dir.resolve("$binDirName/llvm-objdump.exe")).also { objdump ->
        require(objdump.isFile) {
            "GNU objdump was not found under ${msys2Dir.resolve(binDirName).absolutePath}."
        }
    }

internal fun parseWindowsImportedDllNames(
    execOperations: ExecOperations,
    objdumpExecutable: File,
    binary: File,
): List<String> {
    require(binary.isFile) { "Windows binary not found at ${binary.absolutePath}" }

    val stdout = ByteArrayOutputStream()
    execOperations.exec {
        commandLine(objdumpExecutable.absolutePath, "-p", binary.absolutePath)
        standardOutput = stdout
    }

    return stdout.toString(Charsets.UTF_8).lineSequence()
        .filter { it.contains("DLL Name:") }
        .map { it.substringAfter("DLL Name:").trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .toList()
}

internal fun orderWindowsDllsByDependencies(
    execOperations: ExecOperations,
    objdumpExecutable: File,
    dllFiles: Iterable<File>,
): List<File> {
    val candidates = dllFiles
        .filter { it.isFile && it.name.endsWith(".dll", ignoreCase = true) }
        .distinctBy { it.name.lowercase(Locale.ROOT) }
        .sortedBy { it.name.lowercase(Locale.ROOT) }

    if (candidates.isEmpty()) return emptyList()

    val byName = candidates.associateBy { it.name.lowercase(Locale.ROOT) }
    val dependencies = candidates.associateWith { dll ->
        parseWindowsImportedDllNames(execOperations, objdumpExecutable, dll)
            .mapNotNull { byName[it.lowercase(Locale.ROOT)] }
            .filter { dependency -> dependency != dll }
            .distinctBy { dependency -> dependency.name.lowercase(Locale.ROOT) }
            .sortedBy { dependency -> dependency.name.lowercase(Locale.ROOT) }
    }

    val ordered = ArrayList<File>(candidates.size)
    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()

    fun visit(file: File) {
        val key = file.name.lowercase(Locale.ROOT)
        if (key in visited) return
        if (!visiting.add(key)) return

        dependencies.getValue(file).forEach(::visit)

        visiting.remove(key)
        visited += key
        ordered += file
    }

    candidates.forEach(::visit)
    return ordered
}

internal fun isWindowsSystemLibrary(dllName: String): Boolean {
    val normalized = dllName.lowercase(Locale.ROOT)
    return normalized.startsWith("api-ms-win-") ||
        normalized in setOf(
            "advapi32.dll",
            "bcrypt.dll",
            "gdi32.dll",
            "kernel32.dll",
            "ole32.dll",
            "shell32.dll",
            "user32.dll",
            "winmm.dll",
            "ws2_32.dll",
        )
}

/** Libraries that must stay matched to the host ABI, services, configuration, or driver stack. */
internal fun isLinuxHostLibrary(name: String): Boolean {
    if (name.startsWith("linux-vdso.so") || name.startsWith("ld-linux-")) return true
    if (name.startsWith("libnss_") || name.startsWith("libcuda.so")) return true
    if (name.startsWith("libpulsecommon-") && name.endsWith(".so")) return true
    return name in LINUX_HOST_LIBRARIES
}

private val LINUX_HOST_LIBRARIES = setOf(
    // glibc and its loader/runtime family
    "libc.so.6",
    "libm.so.6",
    "libmvec.so.1",
    "libdl.so.2",
    "libpthread.so.0",
    "librt.so.1",
    "libresolv.so.2",
    "libanl.so.1",
    "libutil.so.1",
    "libBrokenLocale.so.1",
    "libthread_db.so.1",
    // compiler runtimes may already be loaded by the JVM
    "libstdc++.so.6",
    "libgcc_s.so.1",
    // audio, fonts, and service clients must use the host configuration and plugins
    "libpulse.so.0",
    "libsystemd.so.0",
    "libasound.so.2",
    "libdbus-1.so.3",
    "libudev.so.1",
    "libfontconfig.so.1",
    // base X11 ABI used by the Compose process
    "libX11.so.6",
    "libX11-xcb.so.1",
    "libxcb.so.1",
    // GL/EGL dispatch and hardware-facing libraries must match the host driver stack
    "libGL.so.1",
    "libEGL.so.1",
    "libGLX.so.0",
    "libOpenGL.so.0",
    "libGLdispatch.so.0",
    "libGLX_mesa.so.0",
    "libglapi.so.0",
    "libdrm.so.2",
    "libgbm.so.1",
    "libvulkan.so.1",
)

/** Verifies that every ELF in a runtime jar is compatible with [MINIMUM_LINUX_GLIBC_VERSION]. */
internal fun verifyLinuxGlibcVersionsInJar(
    execOperations: ExecOperations,
    runtimeJar: File,
    temporaryDir: File,
    minimumVersionName: String = MINIMUM_LINUX_GLIBC_VERSION,
) {
    require(runtimeJar.isFile) { "Linux runtime jar not found at ${runtimeJar.absolutePath}" }
    temporaryDir.deleteRecursively()
    temporaryDir.mkdirs()

    val minimumVersion = parseGlibcVersion(minimumVersionName)
    val violations = mutableListOf<String>()
    var elfCount = 0

    ZipFile(runtimeJar).use { zip ->
        zip.entries().asSequence()
            .filterNot { it.isDirectory }
            .forEachIndexed { index, entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                if (bytes.size < ELF_MAGIC.size || !bytes.copyOfRange(0, ELF_MAGIC.size).contentEquals(ELF_MAGIC)) {
                    return@forEachIndexed
                }
                elfCount++

                val extracted = temporaryDir.resolve("$index-${entry.name.substringAfterLast('/')}")
                extracted.writeBytes(bytes)
                val stdout = ByteArrayOutputStream()
                execOperations.exec {
                    commandLine("readelf", "--version-info", "--wide", extracted.absolutePath)
                    environment("LC_ALL", "C")
                    standardOutput = stdout
                }

                val requiredVersions = parseRequiredGlibcVersions(stdout.toString(Charsets.UTF_8))
                val incompatibleVersions = requiredVersions
                    .filterNot { version -> isGlibcRequirementCompatible(version, minimumVersion) }
                    .sorted()
                if (incompatibleVersions.isNotEmpty()) {
                    violations += "${entry.name}: ${incompatibleVersions.joinToString()}"
                }
            }
    }

    require(elfCount > 0) { "No ELF files found in ${runtimeJar.absolutePath}" }
    require(violations.isEmpty()) {
        "Linux runtime requires glibc newer than the declared $minimumVersionName baseline:\n" +
            violations.joinToString("\n")
    }
}

abstract class VerifyLinuxGlibcBaselineTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeJar: RegularFileProperty

    @get:Input
    abstract val minimumVersion: Property<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun verify() {
        verifyLinuxGlibcVersionsInJar(
            execOperations = execOperations,
            runtimeJar = runtimeJar.get().asFile,
            temporaryDir = temporaryDir,
            minimumVersionName = minimumVersion.get(),
        )
        logger.lifecycle(
            "Verified every ELF in ${runtimeJar.get().asFile.name} against glibc ${minimumVersion.get()}",
        )
    }
}

internal fun parseRequiredGlibcVersions(readelfOutput: String): Set<String> {
    var readingNeeds = false
    return buildSet {
        readelfOutput.lineSequence().forEach { line ->
            if (line.startsWith("Version needs section")) {
                readingNeeds = true
                return@forEach
            }
            if (line.startsWith("Version ") && line.contains(" section ")) {
                readingNeeds = false
                return@forEach
            }
            if (readingNeeds) {
                GLIBC_NEEDED_NAME.findAll(line).forEach { match -> add(match.groupValues[1]) }
            }
        }
    }
}

internal fun isGlibcRequirementCompatible(
    requirement: String,
    baselineVersion: List<Int> = parseGlibcVersion(MINIMUM_LINUX_GLIBC_VERSION),
): Boolean {
    val numericVersion = GLIBC_NUMERIC_VERSION.matchEntire(requirement)?.groupValues?.get(1)
        ?: GLIBC_SPECIAL_VERSION_INTRODUCED[requirement]
        ?: return false
    return compareVersions(parseGlibcVersion(numericVersion), baselineVersion) <= 0
}

private fun parseGlibcVersion(version: String): List<Int> =
    version.split('.').map { component -> component.toInt() }

private fun compareVersions(left: List<Int>, right: List<Int>): Int {
    repeat(maxOf(left.size, right.size)) { index ->
        val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
        if (comparison != 0) return comparison
    }
    return 0
}

private val ELF_MAGIC = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
private val GLIBC_NEEDED_NAME = Regex("\\bName:\\s+(GLIBC_\\S+)")
private val GLIBC_NUMERIC_VERSION = Regex("GLIBC_(\\d+(?:\\.\\d+)+)")
private val GLIBC_SPECIAL_VERSION_INTRODUCED = mapOf("GLIBC_ABI_DT_RELR" to "2.36")
