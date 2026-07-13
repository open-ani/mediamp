/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package nativebuild

import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File

/** Lists the dependent-library install names of a Mach-O file via `otool -L`. */
internal fun machODependencyPaths(execOperations: ExecOperations, machO: File): List<String> {
    val output = ByteArrayOutputStream()
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

/**
 * Rewrites Mach-O load commands to `@loader_path` by dependency *file name* rather than
 * by absolute path. Matching by name is what makes this correct for build-cache-restored
 * libraries: their recorded install names contain the absolute paths of whatever worktree
 * or machine originally built them, so an absolute-path map would silently miss them.
 */
internal fun rewriteMachOToLoaderPath(
    execOperations: ExecOperations,
    machOFiles: List<File>,
    bundledLibraryNames: Set<String>,
) {
    machOFiles.forEach { machO ->
        if (machO.name in bundledLibraryNames) {
            execOperations.exec {
                commandLine(
                    "xcrun", "install_name_tool",
                    "-id", "@loader_path/${machO.name}",
                    machO.absolutePath,
                )
            }
        }
        machODependencyPaths(execOperations, machO)
            .filter { dep -> !dep.startsWith("@") && File(dep).name in bundledLibraryNames }
            .forEach { dep ->
                execOperations.exec {
                    commandLine(
                        "xcrun", "install_name_tool",
                        "-change", dep, "@loader_path/${File(dep).name}",
                        machO.absolutePath,
                    )
                    isIgnoreExitValue = true
                }
            }
    }
}
