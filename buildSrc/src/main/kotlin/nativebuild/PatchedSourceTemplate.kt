package nativebuild

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.UntrackedTask
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Turns a git submodule plus vendored patches into a stable source template for the rest
 * of the build. The patch application happens inside [PrepareSourceTreeTask] itself
 * (apply -> snapshot -> revert around the copy), keeping the submodule clean and the
 * task's inputs deterministic.
 */
internal class PatchedSourceTemplateSpec(
    /** Capitalized module tag used in task names, e.g. "Ffmpeg" -> `applyFfmpegPatches`. */
    val taskNameInfix: String,
    /** Task group and display name, e.g. "ffmpeg". */
    val taskGroup: String,
    val sourceDisplayName: String,
    /** Applied in order; a later patch may build on an earlier one. */
    val patchFiles: List<File>,
    val sourceDir: File,
    val outputDir: Provider<Directory>,
    /** File that must exist in the tree for it to be considered a valid checkout. */
    val markerFileRelativePath: String,
    /**
     * How to undo the patches; differs per module for historical reasons. Defaults to
     * reverse-applying [patchFiles] newest first.
     */
    val revertCommand: List<String>? = null,
    val missingSourceMessage: String? = null,
    val preserveSymbolicLinks: Boolean = false,
    val preserveExecutablePermissions: Boolean = false,
)

internal fun Project.registerPatchedSourceTemplate(
    spec: PatchedSourceTemplateSpec,
): TaskProvider<PrepareSourceTreeTask> {
    validatePatchSeries(spec.sourceDisplayName, spec.patchFiles)
    val hasPatches = spec.patchFiles.isNotEmpty()

    // Standalone developer conveniences for working on the patches themselves; the
    // template task no longer depends on them.
    tasks.register<PatchWorkingTreeTask>("apply${spec.taskNameInfix}Patches") {
        group = spec.taskGroup
        description = "Apply patches to the ${spec.sourceDisplayName} submodule source tree"
        enabled = hasPatches

        patchFiles.from(spec.patchFiles)
        sourceDir.set(spec.sourceDir)
        reverse.set(false)
    }

    val revertCommand = spec.revertCommand
    if (revertCommand != null) {
        tasks.register<Exec>("revert${spec.taskNameInfix}Patches") {
            group = spec.taskGroup
            description = "Revert patches from the ${spec.sourceDisplayName} submodule source tree"
            enabled = hasPatches

            commandLine(revertCommand)
            workingDir = spec.sourceDir
        }
    } else {
        tasks.register<PatchWorkingTreeTask>("revert${spec.taskNameInfix}Patches") {
            group = spec.taskGroup
            description = "Revert patches from the ${spec.sourceDisplayName} submodule source tree"
            enabled = hasPatches

            patchFiles.from(spec.patchFiles)
            sourceDir.set(spec.sourceDir)
            reverse.set(true)
        }
    }

    return tasks.register<PrepareSourceTreeTask>("prepare${spec.taskNameInfix}SourceTemplate") {
        group = spec.taskGroup
        description = "Create a stable ${spec.sourceDisplayName} source snapshot for this build"
        patchFiles.from(spec.patchFiles)
        sourceDir.set(spec.sourceDir)
        outputDir.set(spec.outputDir)
        markerFileRelativePath.set(spec.markerFileRelativePath)
        sourceDisplayName.set(spec.sourceDisplayName)
        spec.missingSourceMessage?.let { missingSourceMessage.set(it) }
        preserveSymbolicLinks.set(spec.preserveSymbolicLinks)
        preserveExecutablePermissions.set(spec.preserveExecutablePermissions)
    }
}

/**
 * Applies or reverts a patch series in the submodule working tree itself, all or nothing,
 * so a patch that fails part-way leaves the tree as it was rather than half patched.
 */
@UntrackedTask(because = "Mutates the submodule working tree in place")
abstract class PatchWorkingTreeTask : DefaultTask() {
    @get:Internal
    abstract val sourceDir: DirectoryProperty

    /** In apply order, also when reverting. */
    @get:Internal
    abstract val patchFiles: ConfigurableFileCollection

    @get:Input
    abstract val reverse: Property<Boolean>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        val git = execOperations.gitPatchRunner(sourceDir.get().asFile)
        val patches = patchFiles.toList()
        if (reverse.get()) {
            revertPatchSeries(patches, git)
        } else {
            applyPatchSeries(patches, git)
        }
    }
}
