package mpv

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import nativebuild.androidTargetName
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

abstract class PrepareMpvAndroidJniLibsTask : DefaultTask() {
    @get:InputDirectory
    abstract val inputDir: org.gradle.api.file.DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: org.gradle.api.file.DirectoryProperty

    @TaskAction
    fun run() {
        project.copy {
            from(inputDir)
            into(outputDir)
        }
    }
}

internal fun registerMpvAndroidJniPackaging(context: MpvBuildContext): TaskProvider<PrepareMpvAndroidJniLibsTask>? {
    if (!context.isBuildVariantEnabled("android")) {
        context.project.logger.lifecycle(
            "Skipping Android JNI packaging tasks: ${context.buildProperties.buildVariantPropertyName} does not include 'android'.",
        )
        return null
    }

    val androidJniCopyTasks = mutableListOf<TaskProvider<out Task>>()
    context.androidAbis.forEach { abi ->
        val targetName = androidTargetName(abi)
        val assembleTaskName = "mpvAssemble$targetName"
        if (!context.project.tasks.names.contains(assembleTaskName)) {
            context.project.logger.lifecycle(
                "Skipping Android JNI packaging for ${abi.abi}: task '$assembleTaskName' is unavailable.",
            )
            return@forEach
        }
        val outputDir = context.project.layout.buildDirectory.dir("mpv-output/$targetName/lib")
        val jniLibsDir = context.project.layout.buildDirectory.dir("generated/mpv-jniLibs/${abi.abi}")

        val copyTask = context.project.tasks.register("copyMpvJniLibs${abi.abi.replace("-", "")}") {
            group = "mpv"
            description = "Copy mpv native libs for Android ${abi.abi}"
            dependsOn(assembleTaskName)
            inputs.dir(outputDir)
            outputs.dir(jniLibsDir)

            doLast {
                val src = outputDir.get().asFile
                val dst = jniLibsDir.get().asFile
                dst.mkdirs()

                src.listFiles()?.filter { it.isFile && it.extension == "so" }?.forEach { file ->
                    file.copyTo(dst.resolve(file.name), overwrite = true)
                }
            }
        }
        androidJniCopyTasks += copyTask
    }

    if (androidJniCopyTasks.isEmpty()) {
        context.project.logger.lifecycle("Skipping merged Android JNI packaging: no Android mpv assemble tasks are available.")
        return null
    }

    return context.project.tasks.register<PrepareMpvAndroidJniLibsTask>("prepareMpvAndroidJniLibs") {
        group = "mpv"
        description = "Prepare merged mpv jniLibs directory for Android variants"
        dependsOn(androidJniCopyTasks)
        inputDir.set(context.project.layout.buildDirectory.dir("generated/mpv-jniLibs"))
        outputDir.set(context.project.layout.buildDirectory.dir("generated/mpv-jniLibs-merged"))
    }
}

internal fun wireMpvAndroidJniPackaging(
    context: MpvBuildContext,
    prepareTask: TaskProvider<PrepareMpvAndroidJniLibsTask>?,
) {
    if (prepareTask == null) return

    val androidComponents = context.project.extensions.findByName("androidComponents") as? KotlinMultiplatformAndroidComponentsExtension
    androidComponents?.onVariants(androidComponents.selector().all()) { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            prepareTask,
            PrepareMpvAndroidJniLibsTask::outputDir,
        )
    }
}
