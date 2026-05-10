/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package ffmpeg

import nativebuild.pathForShell
import nativebuild.shellQuote
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

abstract class FfmpegAppleFrameworkTask : DefaultTask() {
    @get:Input
    abstract val targetName: Property<String>

    @get:Input
    abstract val frameworkName: Property<String>

    @get:Input
    abstract val ffmpegLibNames: ListProperty<String>

    @get:InputDirectory
    abstract val buildDirPath: DirectoryProperty

    @get:InputDirectory
    abstract val installDir: DirectoryProperty

    @get:InputFile
    abstract val wrapperSource: RegularFileProperty

    @get:InputFile
    abstract val publicHeaderSource: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        val frameworkDir = outputDir.get().asFile
        val frameworkNameValue = frameworkName.get()
        val buildDir = buildDirPath.get().asFile
        val installDirFile = installDir.get().asFile
        val publicHeader = publicHeaderSource.get().asFile
        val headerDir = frameworkDir.resolve("Headers")
        val modulesDir = frameworkDir.resolve("Modules")

        frameworkDir.deleteRecursively()
        headerDir.mkdirs()
        modulesDir.mkdirs()

        publicHeader.copyTo(headerDir.resolve(publicHeader.name), overwrite = true)
        modulesDir.resolve("module.modulemap").writeText(buildAppleModuleMap(frameworkNameValue))
        frameworkDir.resolve("Info.plist").writeText(
            buildAppleFrameworkInfoPlist(
                frameworkName = frameworkNameValue,
                targetName = targetName.get(),
            ),
        )

        buildAppleFrameworkBinary(
            execOperations = execOperations,
            logger = logger,
            targetName = targetName.get(),
            frameworkName = frameworkNameValue,
            wrapperSource = wrapperSource.get().asFile,
            buildDir = buildDir,
            installDir = installDirFile,
            frameworkBinary = frameworkDir.resolve(frameworkNameValue),
            ffmpegLibNames = ffmpegLibNames.get(),
        )
    }
}

abstract class FfmpegAppleXcframeworkTask : DefaultTask() {
    @get:Input
    abstract val frameworkName: Property<String>

    @get:InputDirectory
    abstract val iosDeviceFramework: DirectoryProperty

    @get:InputDirectory
    abstract val iosSimulatorFramework: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        val output = outputDir.get().asFile
        output.deleteRecursively()
        output.parentFile.mkdirs()

        execOperations.exec {
            commandLine(
                "xcodebuild",
                "-create-xcframework",
                "-framework",
                iosDeviceFramework.get().asFile.absolutePath,
                "-framework",
                iosSimulatorFramework.get().asFile.absolutePath,
                "-output",
                output.absolutePath,
            )
        }

        logger.lifecycle("Created Apple XCFramework for ${frameworkName.get()} at $output")
    }
}

private fun buildAppleFrameworkBinary(
    execOperations: ExecOperations,
    logger: Logger,
    targetName: String,
    frameworkName: String,
    wrapperSource: File,
    buildDir: File,
    installDir: File,
    frameworkBinary: File,
    ffmpegLibNames: List<String>,
) {
    require(wrapperSource.isFile) {
        "FFmpeg wrapper source not found at ${wrapperSource.absolutePath}"
    }

    val config = readFfmpegConfig(buildDir.resolve("ffbuild/config.mak"))
    val fftoolsDir = buildDir.resolve("fftools")
    val fftoolsObjects = fftoolsDir.walkTopDown()
        .filter { it.isFile && it.extension == "o" }
        .map(File::getAbsolutePath)
        .toList()
    require(fftoolsObjects.isNotEmpty()) {
        "No fftools object files found in $fftoolsDir while building $frameworkName."
    }

    val buildDirPath = shellQuote(pathForShell(buildDir, windowsMsys = false))
    val outputPath = shellQuote(pathForShell(frameworkBinary, windowsMsys = false))
    val wrapperPath = shellQuote(pathForShell(wrapperSource, windowsMsys = false))
    val ffmpegIncludes = listOf(
        installDir.resolve("include"),
        buildDir.resolve("source"),
    )
        .distinctBy { it.absolutePath }
        .onEach { require(it.isDirectory) { "FFmpeg include directory not found at ${it.absolutePath}" } }
        .joinToString(" ") { "-I${shellQuote(pathForShell(it, windowsMsys = false))}" }
    val staticLibraries = ffmpegLibNames.map { libName ->
        installDir.resolve("lib/lib$libName.a").also {
            require(it.isFile) { "Expected FFmpeg static library at ${it.absolutePath}" }
        }
    }.joinToString(" ") { shellQuote(pathForShell(it, windowsMsys = false)) }
    val extraLibs = expandMakeVariables(config["EXTRALIBS"].orEmpty(), config).trim()

    val command = buildString {
        append(expandMakeVariables(config.getValue("CC"), config))
        append(' ')
        append(expandMakeVariables(config["CPPFLAGS"].orEmpty(), config))
        append(' ')
        append(expandMakeVariables(config["CFLAGS"].orEmpty(), config))
        append(' ')
        append(ffmpegIncludes)
        append(' ')
        append("-dynamiclib -Wl,-install_name,@rpath/$frameworkName.framework/$frameworkName")
        append(' ')
        append(expandMakeVariables(config["LDFLAGS"].orEmpty(), config))
        append(" -o ")
        append(outputPath)
        append(' ')
        append(wrapperPath)
        append(' ')
        append(fftoolsObjects.joinToString(" ") { shellQuote(pathForShell(File(it), windowsMsys = false)) })
        append(' ')
        append(staticLibraries)
        append(" -lm -pthread -framework CoreFoundation -framework CoreVideo -framework CoreMedia -framework Security")
        if (extraLibs.isNotEmpty()) {
            append(' ')
            append(extraLibs)
        }
    }

    execOperations.exec {
        commandLine("bash", "-l", "-c", "cd $buildDirPath && $command")
    }

    logger.info("Built Apple FFmpeg framework binary for $targetName: $frameworkBinary")
}

private fun buildAppleModuleMap(frameworkName: String): String = """
framework module $frameworkName {
    umbrella header "$frameworkName.h"

    export *
    module * { export * }
}
""".trimIndent() + "\n"

private fun buildAppleFrameworkInfoPlist(
    frameworkName: String,
    targetName: String,
): String {
    val bundleIdSuffix = targetName.replaceFirstChar { it.lowercase() }
    val supportedPlatform = if (targetName == "IosSimulatorArm64") "iPhoneSimulator" else "iPhoneOS"
    return """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>en</string>
    <key>CFBundleExecutable</key>
    <string>$frameworkName</string>
    <key>CFBundleIdentifier</key>
    <string>org.openani.mediamp.ffmpeg.$bundleIdSuffix</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>$frameworkName</string>
    <key>CFBundlePackageType</key>
    <string>FMWK</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0</string>
    <key>CFBundleSupportedPlatforms</key>
    <array>
        <string>$supportedPlatform</string>
    </array>
    <key>CFBundleVersion</key>
    <string>1</string>
    <key>MinimumOSVersion</key>
    <string>16.0</string>
</dict>
</plist>
""".trimIndent() + "\n"
}
