package nativebuild

import org.gradle.process.ExecOperations
import java.io.File

/** Runs `git apply [--reverse] <patch>` in a fixed working tree. */
internal fun interface GitPatchRunner {
    fun apply(patch: File, reverse: Boolean)
}

internal fun ExecOperations.gitPatchRunner(workingDir: File): GitPatchRunner =
    GitPatchRunner { patch, reverse ->
        exec {
            commandLine(listOfNotNull("git", "apply", "--reverse".takeIf { reverse }, patch.absolutePath))
            this.workingDir = workingDir
        }
    }

/**
 * Patches are identified by file name in logs and up-to-date checks, so a series must not
 * repeat one; it must also exist, so a typo fails at configuration instead of silently
 * dropping a patch.
 */
internal fun validatePatchSeries(sourceDisplayName: String, patches: List<File>) {
    patches.forEach { patch ->
        require(patch.isFile) { "$sourceDisplayName patch not found: ${patch.absolutePath}" }
    }
    val duplicates = patches.groupBy { it.name }.filterValues { it.size > 1 }.keys
    require(duplicates.isEmpty()) { "$sourceDisplayName patch file names must be unique: $duplicates" }
}

/** Applies [patches] in order; all or nothing (see [applyEachOrUndo]). */
internal fun applyPatchSeries(patches: List<File>, git: GitPatchRunner) {
    applyEachOrUndo(patches, reverse = false, git)
}

/** Reverts [patches] (given in apply order) newest first; all or nothing. */
internal fun revertPatchSeries(patches: List<File>, git: GitPatchRunner) {
    applyEachOrUndo(patches.asReversed(), reverse = true, git)
}

/**
 * A single `git apply` is atomic, but several patches in one call are not: a later failure
 * leaves the earlier ones applied. Apply one at a time and, on failure, undo the completed
 * steps newest first before rethrowing, so the tree is left as it was found.
 */
private fun applyEachOrUndo(patches: List<File>, reverse: Boolean, git: GitPatchRunner) {
    val done = mutableListOf<File>()
    try {
        patches.forEach { patch ->
            git.apply(patch, reverse)
            done += patch
        }
    } catch (e: Throwable) {
        done.asReversed().forEach { patch ->
            runCatching { git.apply(patch, !reverse) }.exceptionOrNull()?.let(e::addSuppressed)
        }
        throw e
    }
}
