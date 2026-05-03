package nativebuild

import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

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

internal fun resolveWindowsObjdump(msys2Dir: File): File =
    msys2Dir.resolve("ucrt64/bin/objdump.exe").also { objdump ->
        require(objdump.isFile) {
            "GNU objdump was not found at ${objdump.absolutePath}. Ensure MSYS2 UCRT64 binutils is installed."
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
