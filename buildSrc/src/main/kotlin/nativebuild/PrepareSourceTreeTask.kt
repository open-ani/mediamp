package nativebuild

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class PrepareSourceTreeTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val markerFileRelativePath: Property<String>

    @get:Input
    abstract val sourceDisplayName: Property<String>

    @get:Input
    abstract val preserveSymbolicLinks: Property<Boolean>

    @get:Input
    abstract val preserveExecutablePermissions: Property<Boolean>

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

        require(src.resolve(marker).isFile) {
            "${sourceDisplayName.get()} source tree is missing $marker at ${src.absolutePath}"
        }

        dst.deleteRecursively()
        if (preserveSymbolicLinks.get()) {
            copyTreePreservingLinks(src, dst)
        } else {
            copyTreeRecursively(src, dst)
        }
        if (preserveExecutablePermissions.get()) {
            restoreExecutablePermissions(src, dst)
        }

        logger.lifecycle("Prepared ${sourceDisplayName.get()} source from ${src.absolutePath} to ${dst.absolutePath}")
    }
}
