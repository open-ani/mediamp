package nativebuild

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

abstract class PrepareSourceTreeTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    /**
     * Patches applied to the snapshot, in order. One file per logically separate upstream
     * change, so a patch can be dropped on its own once the change lands upstream. The
     * submodule working tree is only mutated for the duration of this task's action
     * (apply -> copy -> revert in a finally block), so [sourceDir]'s input fingerprint —
     * taken before execution — always describes the clean checkout, and the patches
     * participate in up-to-date checks as their own content-hashed inputs.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    abstract val patchFiles: ListProperty<RegularFile>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val markerFileRelativePath: Property<String>

    @get:Input
    abstract val sourceDisplayName: Property<String>

    @get:Input
    @get:Optional
    abstract val missingSourceMessage: Property<String>

    @get:Input
    abstract val preserveSymbolicLinks: Property<Boolean>

    @get:Input
    abstract val preserveExecutablePermissions: Property<Boolean>

    @get:Inject
    abstract val execOperations: ExecOperations

    init {
        preserveSymbolicLinks.convention(false)
        preserveExecutablePermissions.convention(false)

        outputs.upToDateWhen {
            val preparedDir = outputDir.orNull?.asFile
            val markerPath = markerFileRelativePath.orNull
            preparedDir?.isDirectory == true && markerPath != null && preparedDir.resolve(markerPath).isFile
        }
    }

    @TaskAction
    fun run() {
        val src = sourceDir.get().asFile
        val dst = outputDir.get().asFile
        val marker = markerFileRelativePath.get()
        val patches = patchFiles.getOrElse(emptyList()).map { it.asFile }

        require(src.resolve(marker).isFile) {
            missingSourceMessage.orNull
                ?: "${sourceDisplayName.get()} source tree is missing $marker at ${src.absolutePath}"
        }

        if (patches.isNotEmpty()) {
            // One invocation so the whole set is applied atomically: a patch that no
            // longer matches the checkout leaves the submodule untouched instead of
            // half-patched.
            execOperations.exec {
                commandLine(listOf("git", "apply") + patches.map { it.absolutePath })
                workingDir = src
            }
        }
        try {
            recreateDirectory(dst)
            if (preserveSymbolicLinks.get()) {
                copyTreePreservingLinks(src, dst)
            } else {
                copyTreeRecursively(src, dst)
            }
            if (preserveExecutablePermissions.get()) {
                restoreExecutablePermissions(src, dst)
            }
        } finally {
            if (patches.isNotEmpty()) {
                execOperations.exec {
                    // Reverse order, so a later patch that builds on an earlier one still
                    // matches the tree when it is undone.
                    commandLine(
                        listOf("git", "apply", "--reverse") +
                            patches.reversed().map { it.absolutePath },
                    )
                    workingDir = src
                }
            }
        }

        logger.lifecycle("Prepared ${sourceDisplayName.get()} source from ${src.absolutePath} to ${dst.absolutePath}")
    }
}
