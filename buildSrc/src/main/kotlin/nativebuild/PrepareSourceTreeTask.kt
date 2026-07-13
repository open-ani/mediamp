package nativebuild

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
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
     * Optional patch applied to the snapshot. The submodule working tree is only mutated
     * for the duration of this task's action (apply -> copy -> revert in a finally
     * block), so [sourceDir]'s input fingerprint — taken before execution — always
     * describes the clean checkout, and the patch participates in up-to-date checks as
     * its own content-hashed input.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    abstract val patchFile: RegularFileProperty

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
        val patch = patchFile.orNull?.asFile

        require(src.resolve(marker).isFile) {
            missingSourceMessage.orNull
                ?: "${sourceDisplayName.get()} source tree is missing $marker at ${src.absolutePath}"
        }

        if (patch != null) {
            execOperations.exec {
                commandLine("git", "apply", patch.absolutePath)
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
            if (patch != null) {
                execOperations.exec {
                    commandLine("git", "apply", "--reverse", patch.absolutePath)
                    workingDir = src
                }
            }
        }

        logger.lifecycle("Prepared ${sourceDisplayName.get()} source from ${src.absolutePath} to ${dst.absolutePath}")
    }
}
