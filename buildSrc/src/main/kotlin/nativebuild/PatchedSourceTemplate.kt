package nativebuild

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Turns a git submodule plus an optional vendored patch into a stable source template for
 * the rest of the build. The patch application happens inside [PrepareSourceTreeTask]
 * itself (apply -> snapshot -> revert around the copy), keeping the submodule clean and
 * the task's inputs deterministic.
 */
internal class PatchedSourceTemplateSpec(
    /** Capitalized module tag used in task names, e.g. "Ffmpeg" -> `applyFfmpegPatches`. */
    val taskNameInfix: String,
    /** Task group and display name, e.g. "ffmpeg". */
    val taskGroup: String,
    val sourceDisplayName: String,
    val patchFile: File,
    val sourceDir: File,
    val outputDir: Provider<Directory>,
    /** File that must exist in the tree for it to be considered a valid checkout. */
    val markerFileRelativePath: String,
    /** How to undo the patch; differs per module for historical reasons. */
    val revertCommand: List<String>,
    val missingSourceMessage: String? = null,
    val preserveSymbolicLinks: Boolean = false,
    val preserveExecutablePermissions: Boolean = false,
)

internal fun Project.registerPatchedSourceTemplate(
    spec: PatchedSourceTemplateSpec,
): TaskProvider<PrepareSourceTreeTask> {
    // Standalone developer conveniences for working on the patch itself; the template
    // task no longer depends on them.
    tasks.register<Exec>("apply${spec.taskNameInfix}Patches") {
        group = spec.taskGroup
        description = "Apply patches to the ${spec.sourceDisplayName} submodule source tree"
        enabled = spec.patchFile.exists()

        commandLine("git", "apply", spec.patchFile.absolutePath)
        workingDir = spec.sourceDir
    }

    tasks.register<Exec>("revert${spec.taskNameInfix}Patches") {
        group = spec.taskGroup
        description = "Revert patches from the ${spec.sourceDisplayName} submodule source tree"
        enabled = spec.patchFile.exists()

        commandLine(spec.revertCommand)
        workingDir = spec.sourceDir
    }

    return tasks.register<PrepareSourceTreeTask>("prepare${spec.taskNameInfix}SourceTemplate") {
        group = spec.taskGroup
        description = "Create a stable ${spec.sourceDisplayName} source snapshot for this build"
        if (spec.patchFile.exists()) {
            patchFile.set(spec.patchFile)
        }
        sourceDir.set(spec.sourceDir)
        outputDir.set(spec.outputDir)
        markerFileRelativePath.set(spec.markerFileRelativePath)
        sourceDisplayName.set(spec.sourceDisplayName)
        spec.missingSourceMessage?.let { missingSourceMessage.set(it) }
        preserveSymbolicLinks.set(spec.preserveSymbolicLinks)
        preserveExecutablePermissions.set(spec.preserveExecutablePermissions)
    }
}
