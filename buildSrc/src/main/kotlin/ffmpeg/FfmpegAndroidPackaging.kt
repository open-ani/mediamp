package ffmpeg

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

abstract class PrepareFfmpegAndroidJniLibsTask : DefaultTask() {
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

internal fun registerAndroidJniPackaging(context: FfmpegBuildContext): TaskProvider<PrepareFfmpegAndroidJniLibsTask>? {
    if (!context.isBuildVariantEnabled("android")) {
        context.project.logger.lifecycle("Skipping Android JNI packaging tasks: mediamp.ffmpeg.buildvariant does not include 'android'.")
        return null
    }

    val androidJniCopyTasks = mutableListOf<TaskProvider<out Task>>()
    context.androidAbis.forEach { abi ->
        val targetName = "Android${abi.abi.replace("-", "")}"
        val assembleTaskName = "ffmpegAssemble$targetName"
        val outputDir = context.project.layout.buildDirectory.dir("ffmpeg-output/$targetName")
        val jniLibsDir = context.project.layout.buildDirectory.dir("generated/ffmpeg-jniLibs/${abi.abi}")

        val copyTask = context.project.tasks.register("copyFfmpegJniLibs${abi.abi.replace("-", "")}") {
            group = "ffmpeg"
            description = "Copy FFmpeg native libs for Android ${abi.abi}"
            if (context.project.tasks.names.contains(assembleTaskName)) {
                dependsOn(assembleTaskName)
            }
            inputs.dir(outputDir)
            outputs.dir(jniLibsDir)

            doLast {
                val src = outputDir.get().asFile
                val dst = jniLibsDir.get().asFile
                dst.mkdirs()

                src.listFiles()?.filter { it.extension == "so" }?.forEach { file ->
                    file.copyTo(dst.resolve(file.name), overwrite = true)
                }

                val ffmpegBin = src.resolve("ffmpeg")
                if (ffmpegBin.exists()) {
                    ffmpegBin.copyTo(dst.resolve("libffmpeg.so"), overwrite = true)
                }
            }
        }
        androidJniCopyTasks += copyTask
    }

    return context.project.tasks.register<PrepareFfmpegAndroidJniLibsTask>("prepareFfmpegAndroidJniLibs") {
        group = "ffmpeg"
        description = "Prepare merged FFmpeg jniLibs directory for Android variants"
        dependsOn(androidJniCopyTasks)
        inputDir.set(context.project.layout.buildDirectory.dir("generated/ffmpeg-jniLibs"))
        outputDir.set(context.project.layout.buildDirectory.dir("generated/ffmpeg-jniLibs-merged"))
    }
}

internal fun wireAndroidJniPackaging(
    context: FfmpegBuildContext,
    prepareTask: TaskProvider<PrepareFfmpegAndroidJniLibsTask>?,
) {
    if (prepareTask == null) return

    val androidComponents = context.project.extensions.findByName("androidComponents") as? KotlinMultiplatformAndroidComponentsExtension
    androidComponents?.onVariants(androidComponents.selector().all()) { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            prepareTask,
            PrepareFfmpegAndroidJniLibsTask::outputDir,
        )
    }
}
