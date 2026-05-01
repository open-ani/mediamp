package ffmpeg

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import java.util.jar.JarFile
import javax.inject.Inject

abstract class PrepareFfmpegAndroidJniLibsTask @Inject constructor(
    private val fs: FileSystemOperations,
) : DefaultTask() {
    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        fs.copy {
            from(inputDir)
            into(outputDir)
        }
    }
}

/**
 * Extracts JavaCPP JNI-bridge `.so` files (libjni*.so) from classifier jars.
 * Outputs are placed under [outputDir]/lib/<abi>/ so they can be consumed
 * per-ABI by the downstream copy tasks.
 */
abstract class ExtractJavaCppJniTask : DefaultTask() {
    @get:InputFiles
    abstract val javacppJars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val baseDir = outputDir.get().asFile
        baseDir.mkdirs()

        javacppJars.forEach { jarFile ->
            JarFile(jarFile).use { jar ->
                jar.entries().asSequence()
                    .filter { entry ->
                        entry.name.endsWith(".so") &&
                            entry.name.substringAfterLast('/').startsWith("libjni")
                    }
                    .forEach { entry ->
                        val relative = entry.name // e.g. lib/arm64-v8a/libjniavutil.so
                        val outFile = baseDir.resolve(relative)
                        outFile.parentFile.mkdirs()
                        jar.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
            }
        }
    }
}

internal fun registerAndroidJniPackaging(context: FfmpegBuildContext): TaskProvider<PrepareFfmpegAndroidJniLibsTask>? {
    if (!context.isBuildVariantEnabled("android")) {
        context.project.logger.lifecycle(
            "Skipping Android JNI packaging tasks: ${context.buildProperties.buildVariantPropertyName} does not include 'android'.",
        )
        return null
    }

    val javacppNativeConfig = context.project.configurations.findByName("javacppNative")
    val extractJavaCppTask = context.project.tasks.register<ExtractJavaCppJniTask>("extractJavaCppJniLibs") {
        group = "ffmpeg"
        description = "Extract JavaCPP JNI bridge .so files from classifier jars"
        javacppJars.from(javacppNativeConfig)
        outputDir.set(context.project.layout.buildDirectory.dir("generated/javacpp-jniLibs"))
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
            dependsOn(extractJavaCppTask)
            inputs.dir(outputDir)
            outputs.dir(jniLibsDir)

            doLast {
                val src = outputDir.get().asFile
                val dst = jniLibsDir.get().asFile
                dst.mkdirs()

                // 1. Copy self-built FFmpeg .so files.
                src.listFiles()?.filter { it.extension == "so" }?.forEach { file ->
                    file.copyTo(dst.resolve(file.name), overwrite = true)
                }

                // 2. Copy JavaCPP JNI bridge .so files extracted earlier.
                val javaCppAbiDir = extractJavaCppTask.get().outputDir.get().asFile
                    .resolve("lib/${abi.abi}")
                if (javaCppAbiDir.isDirectory) {
                    javaCppAbiDir.listFiles()
                        ?.filter { it.name.startsWith("libjni") && it.extension == "so" }
                        ?.forEach { file ->
                            file.copyTo(dst.resolve(file.name), overwrite = true)
                        }
                }

                // 3. Copy ffmpeg CLI binary as a loadable .so for subprocess fallback.
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
