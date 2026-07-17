/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package wsola

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import Os
import getOs
import getPropertyOrNull
import nativebuild.DEFAULT_ANDROID_ABIS
import nativebuild.androidNdkHostTag
import nativebuild.resolveAndroidAbis
import nativebuild.resolveNdkDir
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register


/**
 * Builds `libmediamp_wsola.so` (WSOLA time-stretch for the ExoPlayer backend) with the
 * NDK LLVM clang++, one task per Android ABI, and injects the result into the module's
 * jniLibs via `variant.sources.jniLibs` (same mechanism as the mpv packaging).
 *
 * Properties:
 *  - `-Pmediamp.exoplayer.wsola.skip=true` disables everything (Kotlin falls back to Sonic).
 *  - `-Pmediamp.exoplayer.wsola.androidabis=arm64-v8a,x86_64` restricts the ABI set.
 */

abstract class CompileWsolaLibraryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val compiler: RegularFileProperty

    @get:Input
    abstract val compilerArgs: ListProperty<String>

    @get:Input
    abstract val windowsHost: Property<Boolean>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        val sources = sourceDir.get().asFile.listFiles()
            ?.filter { it.extension == "cpp" }
            ?.sortedBy { it.name }
            .orEmpty()
        require(sources.isNotEmpty()) { "No .cpp sources in ${sourceDir.get().asFile}" }
        val launcher = if (windowsHost.get()) listOf("cmd.exe", "/d", "/c") else emptyList()
        val command = launcher +
            compiler.get().asFile.absolutePath +
            compilerArgs.get() +
            sources.map { it.absolutePath } +
            listOf("-o", out.absolutePath)
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException(
                "WSOLA compile failed (exit $exitCode): ${command.joinToString(" ")}\n$output",
            )
        }
        logger.info(output)
    }
}

abstract class PrepareWsolaAndroidJniLibsTask : DefaultTask() {
    @get:InputFiles
    abstract val inputFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        project.sync {
            inputFiles.files.sortedBy { it.parentFile.name }.forEach { library ->
                from(library) {
                    into(library.parentFile.name)
                }
            }
            into(outputDir)
        }
    }
}

fun Project.configureWsolaAndroidBuild() {
    if (getPropertyOrNull("mediamp.exoplayer.wsola.skip")?.toBoolean() == true) {
        logger.lifecycle("Skipping WSOLA native build: mediamp.exoplayer.wsola.skip=true")
        return
    }
    val ndkDir = runCatching { resolveNdkDir() }.getOrElse {
        logger.warn("Android NDK not found – skipping WSOLA native build. Set ndk.dir or ANDROID_NDK_HOME to enable.")
        return
    }
    val hostOs = getOs()
    val hostTag = androidNdkHostTag(hostOs)
    val windowsHost = hostOs == Os.Windows
    val llvmBinDir = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/bin")
    require(llvmBinDir.isDirectory) { "NDK LLVM toolchain not found at '$llvmBinDir'." }
    val sysroot = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/sysroot")

    val abis = resolveAndroidAbis(
        propertyNames = listOf("mediamp.exoplayer.wsola.androidabis"),
        availableAbis = DEFAULT_ANDROID_ABIS,
    )

    val compileTasks = mutableListOf<TaskProvider<CompileWsolaLibraryTask>>()
    abis.forEach { abi ->
        val taskName = "compileWsola${abi.abi.replace("-", "")}"
        compileTasks += tasks.register<CompileWsolaLibraryTask>(taskName) {
            group = "mediamp"
            description = "Compile libmediamp_wsola.so for Android ${abi.abi}"
            val compilerSuffix = if (windowsHost) ".cmd" else ""
            compiler.set(
                llvmBinDir.resolve("${abi.clangTriple}${abi.apiLevel}-clang++$compilerSuffix"),
            )
            this.windowsHost.set(windowsHost)
            compilerArgs.set(
                listOf(
                    "--sysroot=${sysroot.absolutePath}",
                    "-std=c++17",
                    "-O2",
                    "-DNDEBUG",
                    "-Wall",
                    "-Wextra",
                    "-Werror",
                    "-fPIC",
                    "-shared",
                    "-Wl,-z,max-page-size=16384",
                    "-llog",
                ),
            )
            sourceDir.set(layout.projectDirectory.dir("src/cpp"))
            outputFile.set(
                layout.buildDirectory.file("generated/wsola-jniLibs/${abi.abi}/libmediamp_wsola.so"),
            )
        }
    }

    val prepareTask = tasks.register<PrepareWsolaAndroidJniLibsTask>("prepareWsolaAndroidJniLibs") {
        group = "mediamp"
        description = "Prepare merged WSOLA jniLibs directory for Android variants"
        dependsOn(compileTasks)
        inputFiles.from(compileTasks.map { it.flatMap(CompileWsolaLibraryTask::outputFile) })
        outputDir.set(layout.buildDirectory.dir("generated/wsola-jniLibs-merged"))
    }

    val androidComponents =
        extensions.findByName("androidComponents") as? KotlinMultiplatformAndroidComponentsExtension
    androidComponents?.onVariants(androidComponents.selector().all()) { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            prepareTask,
            PrepareWsolaAndroidJniLibsTask::outputDir,
        )
    }
}
