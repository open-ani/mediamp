package nativebuild

import java.io.File

internal fun String.toMsysPath(): String {
    val normalized = replace("\\", "/")
    return if (normalized.length >= 2 && normalized[1] == ':') {
        "/${normalized[0].lowercaseChar()}${normalized.substring(2)}"
    } else {
        normalized
    }
}

internal fun pathForShell(file: File, windowsMsys: Boolean): String =
    if (windowsMsys) file.absolutePath.toMsysPath() else file.absolutePath

internal fun shellQuote(value: String): String =
    "'${value.replace("'", "'\"'\"'")}'"

internal fun jniIncludeFlags(
    targetName: String,
    windowsMsys: Boolean,
): List<String> {
    @Suppress("UNUSED_VARIABLE")
    val ignoredTargetName = targetName
    val javaHome = System.getenv("JAVA_HOME")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?: File(System.getProperty("java.home"))
    val includeDir = javaHome.resolve("include")
    val platformDir = includeDir.resolve(
        currentJniPlatformIncludeDirName(),
    )
    return listOf(includeDir, platformDir)
        .onEach { require(it.isDirectory) { "JNI include directory not found at ${it.absolutePath}" } }
        .map { "-I${pathForShell(it, windowsMsys)}" }
}

private fun currentJniPlatformIncludeDirName(): String =
    when {
        System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true) -> "win32"
        System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true) -> "darwin"
        else -> "linux"
    }

internal fun shellScriptWithExports(
    env: Map<String, String>,
    command: String,
): String = buildString {
    append("set -e\n")
    env.forEach { (key, value) ->
        append("export ")
        append(key)
        append("=")
        append(shellQuote(value))
        append('\n')
    }
    append(command)
}
