/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package nativebuild

import org.gradle.api.logging.Logger
import java.io.File

/**
 * All textual spellings of [root] that may appear in generated files: the native form,
 * the forward-slash form and (on Windows) the MSYS form (`/c/...`).
 */
internal fun absolutePathVariants(root: File): List<String> {
    val native = root.absolutePath
    val forward = native.replace('\\', '/')
    val variants = mutableListOf(native, forward)
    if (forward.length > 2 && forward[1] == ':') {
        variants.add("/" + forward[0].lowercaseChar() + forward.substring(2))
    }
    return variants.distinct().sortedByDescending { it.length }
}

/**
 * Replaces worktree-specific absolute paths in [text] with a stable token so that equal
 * builds in different worktrees/machines produce equal build-cache keys.
 */
internal fun sanitizeAbsolutePaths(text: String, roots: List<File>): String {
    var result = text
    roots.flatMap { absolutePathVariants(it) }
        .distinct()
        .sortedByDescending { it.length }
        .forEach { variant -> result = result.replace(variant, "<PATH>") }
    return result
}

/**
 * Rewrites pkg-config files under `[installDir]/lib/pkgconfig` to be relocatable:
 * `prefix` becomes `${'$'}{pcfiledir}/../..` and every other absolute reference to the
 * install prefix becomes `${'$'}{prefix}`.
 *
 * Required by the build cache: entries are shared across worktrees and machines, so the
 * installed metadata must not point back at the absolute directory the entry was produced
 * in. It also keeps downstream input fingerprints (mpv consumes the FFmpeg install via
 * `PKG_CONFIG_LIBDIR`) identical across worktrees, which is what makes cross-worktree
 * cache hits possible in the first place.
 */
internal fun makePkgConfigRelocatable(installDir: File, logger: Logger) {
    val pkgConfigDir = installDir.resolve("lib/pkgconfig")
    if (!pkgConfigDir.isDirectory) return

    val variants = absolutePathVariants(installDir)
    var rewrittenFiles = 0

    pkgConfigDir.listFiles()
        ?.filter { it.isFile && it.extension == "pc" }
        .orEmpty()
        .forEach { pcFile ->
            val original = pcFile.readText()
            val relocated = original.lineSequence().joinToString("\n") { line ->
                if (line.startsWith("prefix=")) {
                    "prefix=\${pcfiledir}/../.."
                } else {
                    var updated = line
                    variants.forEach { variant -> updated = updated.replace(variant, "\${prefix}") }
                    updated
                }
            }
            if (relocated != original) {
                pcFile.writeText(relocated)
                rewrittenFiles += 1
            }
        }

    if (rewrittenFiles > 0) {
        logger.lifecycle(
            "Rewrote $rewrittenFiles pkg-config file(s) under ${pkgConfigDir.absolutePath} to be relocatable.",
        )
    }
}
