package nativebuild

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

internal fun copyTreeRecursively(source: File, target: File) {
    if (!source.exists()) return

    source.walkTopDown().forEach { input ->
        val relative = input.relativeTo(source)
        val output = if (relative.path.isEmpty()) target else target.resolve(relative.path)
        if (input.isDirectory) {
            output.mkdirs()
        } else {
            output.parentFile.mkdirs()
            input.copyTo(output, overwrite = true)
        }
    }
}

internal fun copyTreePreservingLinks(source: File, target: File) {
    if (!source.exists()) return

    Files.walk(source.toPath()).forEach { srcPath ->
        val relative = source.toPath().relativize(srcPath)
        val dstPath = target.toPath().resolve(relative.toString())

        when {
            Files.isSymbolicLink(srcPath) -> {
                Files.createDirectories(dstPath.parent)
                val linkTarget = Files.readSymbolicLink(srcPath)
                runCatching {
                    Files.deleteIfExists(dstPath)
                    Files.createSymbolicLink(dstPath, linkTarget)
                }.getOrElse {
                    val realSource = srcPath.toRealPath()
                    Files.copy(realSource, dstPath, StandardCopyOption.REPLACE_EXISTING)
                }
            }

            Files.isDirectory(srcPath, LinkOption.NOFOLLOW_LINKS) -> {
                Files.createDirectories(dstPath)
            }

            else -> {
                Files.createDirectories(dstPath.parent)
                Files.copy(
                    srcPath,
                    dstPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES,
                )
            }
        }
    }
}

internal fun recreateDirectory(dir: File) {
    deleteRecursivelyForce(dir)
    dir.mkdirs()
}

internal fun deleteRecursivelyForce(target: File) {
    if (!target.exists()) return

    target.walkBottomUp().forEach { entry ->
        if (!entry.exists()) return@forEach

        entry.setWritable(true, false)
        repeat(6) { attempt ->
            if (entry.delete() || !entry.exists()) {
                return@forEach
            }
            if (attempt == 5) {
                error("Failed to delete ${entry.absolutePath}")
            }
            Thread.sleep(50L * (attempt + 1))
        }
    }
}

internal fun restoreExecutablePermissions(sourceDir: File, targetDir: File) {
    sourceDir.walkTopDown()
        .filter { src ->
            src.isFile && (
                src.canExecute() ||
                    src.name == "configure" ||
                    src.extension == "sh"
                )
        }
        .forEach { src ->
            val relative = src.relativeTo(sourceDir)
            val target = targetDir.resolve(relative.path)
            if (target.exists()) {
                target.setExecutable(true)
            }
        }
}
