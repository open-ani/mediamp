package nativebuild

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Turns a git submodule plus its vendored patches into a stable source template for the
 * rest of the build. Patch application happens inside [PrepareSourceTreeTask] itself
 * (apply -> snapshot -> revert around the copy), keeping the submodule clean and the
 * task's inputs deterministic.
 */
internal class PatchedSourceTemplateSpec(
    /** Capitalized module tag used in task names, e.g. "Ffmpeg" -> `applyFfmpegPatches`. */
    val taskNameInfix: String,
    /** Task group and display name, e.g. "ffmpeg". */
    val taskGroup: String,
    val sourceDisplayName: String,
    /** Applied in order; missing files are skipped, so an empty list means "no patches". */
    val patchFiles: List<File>,
    val sourceDir: File,
    val outputDir: Provider<Directory>,
    /** File that must exist in the tree for it to be considered a valid checkout. */
    val markerFileRelativePath: String,
    /**
     * How to undo the applied patches; differs per module for historical reasons.
     * Receives the patches that actually exist, in application order.
     */
    val revertCommand: (List<File>) -> List<String>,
    val missingSourceMessage: String? = null,
    val preserveSymbolicLinks: Boolean = false,
    val preserveExecutablePermissions: Boolean = false,
)

internal fun Project.registerPatchedSourceTemplate(
    spec: PatchedSourceTemplateSpec,
): TaskProvider<PrepareSourceTreeTask> {
    val patches = spec.patchFiles.filter(File::exists)

    // Standalone developer conveniences for working on the patches themselves; the
    // template task no longer depends on them.
    tasks.register<Exec>("apply${spec.taskNameInfix}Patches") {
        group = spec.taskGroup
        description = "Apply patches to the ${spec.sourceDisplayName} submodule source tree"
        enabled = patches.isNotEmpty()

        commandLine(listOf("git", "apply") + patches.map(File::getAbsolutePath))
        workingDir = spec.sourceDir
    }

    tasks.register<Exec>("revert${spec.taskNameInfix}Patches") {
        group = spec.taskGroup
        description = "Revert patches from the ${spec.sourceDisplayName} submodule source tree"
        enabled = patches.isNotEmpty()

        commandLine(spec.revertCommand(patches))
        workingDir = spec.sourceDir
    }

    return tasks.register<PrepareSourceTreeTask>("prepare${spec.taskNameInfix}SourceTemplate") {
        group = spec.taskGroup
        description = "Create a stable ${spec.sourceDisplayName} source snapshot for this build"
        patchFiles.set(patches.map { layout.projectDirectory.file(it.absolutePath) })
        sourceDir.set(spec.sourceDir)
        outputDir.set(spec.outputDir)
        markerFileRelativePath.set(spec.markerFileRelativePath)
        sourceDisplayName.set(spec.sourceDisplayName)
        spec.missingSourceMessage?.let { missingSourceMessage.set(it) }
        preserveSymbolicLinks.set(spec.preserveSymbolicLinks)
        preserveExecutablePermissions.set(spec.preserveExecutablePermissions)
    }
}
