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

/**
 * Linux system-baseline libraries that the desktop runtime intentionally does NOT bundle.
 * They are either not safely relocatable (glibc and the dynamic loader), coupled to the
 * machine's drivers or desktop services (GL/Vulkan/VAAPI, X11, audio servers, D-Bus,
 * fontconfig), or universally present with a long-stable soname (zlib, libstdc++).
 * Everything else reported by `ldd` (libplacebo, libass, libshaderc, ...) is collected
 * into the runtime directory so the package does not depend on the sonames installed on
 * the target machine.
 */
internal fun isLinuxSystemLibrary(soname: String): Boolean {
    val name = soname.lowercase(Locale.ROOT)
    return name in LINUX_SYSTEM_LIBRARY_NAMES ||
            LINUX_SYSTEM_LIBRARY_PREFIXES.any { name.startsWith(it) }
}

private val LINUX_SYSTEM_LIBRARY_NAMES = setOf(
    // Dynamic loader / glibc. Not relocatable, always resolved via the system loader.
    "linux-vdso.so.1",
    "libc.so.6",
    "libm.so.6",
    "libmvec.so.1",
    "libdl.so.2",
    "librt.so.1",
    "libpthread.so.0",
    "libresolv.so.2",
    "libutil.so.1",
    "libnsl.so.2",
    "libanl.so.1",
    // Toolchain runtime. Backward compatible like glibc; the build machine is the floor.
    "libstdc++.so.6",
    "libgcc_s.so.1",
    // zlib's soname has been stable for decades; part of every desktop baseline.
    "libz.so.1",
    // GPU/driver stack. Coupled to the machine's drivers, never relocatable.
    "libgl.so.1",
    "libegl.so.1",
    "libglesv2.so.2",
    "libglx.so.0",
    "libgldispatch.so.0",
    "libopengl.so.0",
    "libvulkan.so.1",
    "libdrm.so.2",
    "libgbm.so.1",
    "libva.so.2",
    "libva-x11.so.2",
    "libva-drm.so.2",
    "libva-wayland.so.2",
    "libvdpau.so.1",
    "libcuda.so.1",
    // X11 client libraries. Part of the desktop baseline.
    "libx11.so.6",
    "libx11-xcb.so.1",
    "libxext.so.6",
    "libxrandr.so.2",
    "libxss.so.1",
    "libxpresent.so.1",
    "libxfixes.so.3",
    "libxinerama.so.1",
    "libxrender.so.1",
    "libxdamage.so.1",
    "libxcomposite.so.1",
    "libxcursor.so.1",
    "libxi.so.6",
    "libxtst.so.6",
    "libxau.so.6",
    "libxdmcp.so.6",
    "libice.so.6",
    "libsm.so.6",
    // Audio servers. Coupled to the running sound daemon.
    "libasound.so.2",
    "libpulse.so.0",
    "libpulse-simple.so.0",
    "libpipewire-0.3.so.0",
    // Desktop services / configuration-coupled.
    "libdbus-1.so.3",
    "libsystemd.so.0",
    "libfontconfig.so.1",
    "libexpat.so.1",
    "libuuid.so.1",
)

private val LINUX_SYSTEM_LIBRARY_PREFIXES = listOf(
    "ld-linux",
    "ld-musl",
    "libnss_",
    "libxcb",
    "libwayland-",
    "libnvidia-",
)
