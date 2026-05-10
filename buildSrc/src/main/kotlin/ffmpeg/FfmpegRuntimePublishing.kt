package ffmpeg

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import configurePom
import nativebuild.DesktopRuntimeTarget
import nativebuild.PublishedArtifact
import nativebuild.addCompilePomDependency
import nativebuild.artifactSuffix
import nativebuild.createDependencyOnlyDesktopRuntimeElements
import nativebuild.isSharedRuntimeLibrary
import nativebuild.manifestRelativePath
import nativebuild.orderLibrariesByPrefixes
import nativebuild.orderWindowsDllsByDependencies
import nativebuild.publicationSuffix
import nativebuild.publishDesktopRuntimeAggregator
import nativebuild.registerCompositeDesktopRuntimeElements
import nativebuild.resolveWindowsObjdump
import nativebuild.wireDesktopRuntimeDependencyConstraints
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.process.ExecOperations
import signAllPublicationsIfEnabled

private const val APPLE_XCFRAMEWORK_ARTIFACT_ID = "mediamp-ffmpeg-runtime-ios-xcframework"

internal fun registerDesktopRuntimeJarTasks(
    context: FfmpegBuildContext,
): Map<DesktopRuntimeTarget, PublishedArtifact> {
    val runtimeJarTasks = linkedMapOf<DesktopRuntimeTarget, PublishedArtifact>()

    context.desktopRuntimeTargets.forEach { target ->
        val taskName = "ffmpegRuntimeJar${target.publicationSuffix()}"
        val assembleTaskName = "ffmpegAssemble${target.targetName}"
        if (context.project.tasks.names.contains(assembleTaskName)) {
            val outputDir = context.project.layout.buildDirectory.dir("ffmpeg-output/${target.targetName}")

            val jarTask = context.project.tasks.register<Jar>(taskName) {
                group = "ffmpeg"
                description = "Package FFmpeg runtime for ${target.artifactSuffix()}"
                archiveBaseName.set("mediamp-ffmpeg-runtime")
                archiveClassifier.set(target.artifactSuffix())
                dependsOn(assembleTaskName)

                from(outputDir) {
                    exclude("include/**")
                }

                doFirst {
                    val dir = outputDir.get().asFile
                    if (dir.isDirectory) {
                        val manifest = runtimeManifestEntries(
                            outputDir = dir,
                            target = target,
                            execOperations = context.project.serviceOf<ExecOperations>(),
                            msys2Dir = if (target.os == "windows") context.msys2Dir else null,
                        ).joinToString("\n")
                        val manifestFile = temporaryDir.resolve("ffmpeg-natives.txt")
                        manifestFile.parentFile.mkdirs()
                        manifestFile.writeText(manifest)
                        from(manifestFile)
                    }
                }
            }
            runtimeJarTasks[target] = PublishedArtifact(jarTask.flatMap { it.archiveFile }, jarTask)
            return@forEach
        }

        val prebuiltJar = context.project.layout.buildDirectory.file(
            "prebuilt-runtime-jars/${target.artifactSuffix()}/mediamp-ffmpeg-runtime-${context.project.version}-${target.artifactSuffix()}.jar",
        ).get().asFile
        if (prebuiltJar.isFile) {
            context.project.logger.lifecycle(
                "Using prebuilt runtime jar for ${target.artifactSuffix()}: ${prebuiltJar.absolutePath}",
            )
            runtimeJarTasks[target] = PublishedArtifact(prebuiltJar)
            return@forEach
        }

        context.project.logger.lifecycle(
            "Skipping runtime publication task for ${target.artifactSuffix()}: neither $assembleTaskName nor ${prebuiltJar.absolutePath} is available.",
        )
    }

    return runtimeJarTasks
}

internal fun registerAppleXcframeworkArtifact(
    context: FfmpegBuildContext,
): PublishedArtifact? {
    if (!context.project.tasks.names.contains("ffmpegCreateAppleXcframework")) {
        context.project.logger.lifecycle(
            "Skipping Apple XCFramework publication artifact: ffmpegCreateAppleXcframework is not available on this host.",
        )
        return null
    }

    val xcframeworkDir = context.project.layout.buildDirectory.dir(
        "apple-xcframework/${context.appleFrameworkName}.xcframework",
    )
    val zipTask = context.project.tasks.register<Zip>("ffmpegAppleXcframeworkZip") {
        group = "ffmpeg"
        description = "Package Apple XCFramework for Maven publication"
        archiveBaseName.set(APPLE_XCFRAMEWORK_ARTIFACT_ID)
        archiveVersion.set(context.project.version.toString())
        destinationDirectory.set(context.project.layout.buildDirectory.dir("distributions"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        dependsOn("ffmpegCreateAppleXcframework")

        from(xcframeworkDir) {
            into("${context.appleFrameworkName}.xcframework")
        }
    }
    return PublishedArtifact(zipTask.flatMap { it.archiveFile }, zipTask)
}

internal fun configureRuntimePublishing(
    context: FfmpegBuildContext,
    desktopRuntimeJarTasks: Map<DesktopRuntimeTarget, PublishedArtifact>,
    appleXcframeworkArtifact: PublishedArtifact?,
) {
    val deployVersion = context.project.version.toString()
    val runtimeTargets = context.desktopRuntimeTargets

    context.project.extensions.getByType<com.vanniktech.maven.publish.MavenPublishBaseExtension>().apply {
        configure(KotlinMultiplatform(JavadocJar.Empty(), SourcesJar.Sources(), listOf("debug", "release")))
        publishToMavenCentral()
        signAllPublicationsIfEnabled(context.project)
        configurePom(context.project)
    }

    context.project.extensions.getByType<PublishingExtension>().publications.apply {
        desktopRuntimeJarTasks.forEach { (target, jarTask) ->
            create<MavenPublication>("ffmpegRuntime${target.publicationSuffix()}") {
                groupId = "org.openani.mediamp"
                artifactId = "mediamp-ffmpeg-runtime-${target.artifactSuffix()}"
                version = deployVersion

                artifact(jarTask.artifactNotation) {
                    classifier = null
                    jarTask.builtBy?.let { builtBy(it) }
                }

                addCompilePomDependency(
                    groupId = "org.openani.mediamp",
                    artifactId = "mediamp-ffmpeg",
                    version = deployVersion,
                )
            }
        }

        appleXcframeworkArtifact?.let { xcframeworkArtifact ->
            create<MavenPublication>("ffmpegRuntimeIosXcframework") {
                groupId = "org.openani.mediamp"
                artifactId = APPLE_XCFRAMEWORK_ARTIFACT_ID
                version = deployVersion

                artifact(xcframeworkArtifact.artifactNotation) {
                    extension = "zip"
                    classifier = null
                    xcframeworkArtifact.builtBy?.let { builtBy(it) }
                }
            }
        }
    }

    desktopRuntimeJarTasks.forEach { (target, jarTask) ->
        context.project.registerCompositeDesktopRuntimeElements(
            configurationName = "ffmpegCompositeRuntimeElements-${target.artifactSuffix()}",
            moduleName = "mediamp-ffmpeg-runtime-${target.artifactSuffix()}",
            runtimeJarArtifact = jarTask,
            target = target,
        )
    }

    val allRuntimeVariants = runtimeTargets.map { target ->
        context.project.createDependencyOnlyDesktopRuntimeElements(
            configurationName = "ffmpegRuntimeElements-${target.artifactSuffix()}",
            dependencyNotation = "org.openani.mediamp:mediamp-ffmpeg-runtime-${target.artifactSuffix()}:$deployVersion",
            target = target,
        )
    }

    context.project.publishDesktopRuntimeAggregator(
        componentName = "ffmpegRuntimeElements",
        publicationName = "ffmpegRuntime",
        groupId = "org.openani.mediamp",
        artifactId = "mediamp-ffmpeg-runtime",
        version = deployVersion,
        variantConfigurations = allRuntimeVariants,
    )

    context.project.wireDesktopRuntimeDependencyConstraints(
        runtimeTargets.map { target ->
            "org.openani.mediamp:mediamp-ffmpeg-runtime-${target.artifactSuffix()}:$deployVersion"
        },
    )
}

private fun runtimeManifestEntries(
    outputDir: java.io.File,
    target: DesktopRuntimeTarget,
    execOperations: ExecOperations,
    msys2Dir: java.io.File?,
): List<String> {
    val allFiles = outputDir.walkTopDown()
        .filter { candidate -> candidate.isFile && "include" !in candidate.relativeTo(outputDir).invariantPathSegments() }
        .toList()
    val sharedLibraries = allFiles.filter { candidate -> isSharedRuntimeLibrary(candidate, target.os) }
    val wrapperFiles = sharedLibraries.filter { candidate -> candidate.name.equals(wrapperLibraryName(target.os), ignoreCase = true) }
    val dependencyLibraries = sharedLibraries - wrapperFiles.toSet()

    val orderedShared = when (target.os) {
        "windows" -> {
            val msys2Root = msys2Dir ?: error("MSYS2 directory must be available when packaging Windows FFmpeg runtimes.")
            orderWindowsDllsByDependencies(
                execOperations = execOperations,
                objdumpExecutable = resolveWindowsObjdump(msys2Root),
                dllFiles = dependencyLibraries,
            )
        }

        else -> orderLibrariesByPrefixes(
            files = dependencyLibraries,
            orderedPrefixes = listOf(
                sharedLibraryFamilyName(target.os, "avutil"),
                sharedLibraryFamilyName(target.os, "swresample"),
                sharedLibraryFamilyName(target.os, "swscale"),
                sharedLibraryFamilyName(target.os, "avcodec"),
                sharedLibraryFamilyName(target.os, "avformat"),
                sharedLibraryFamilyName(target.os, "avfilter"),
                sharedLibraryFamilyName(target.os, "avdevice"),
            ),
            unmatchedFirst = false,
        )
    }

    val orderedNonShared = (allFiles - sharedLibraries.toSet())
        .sortedBy { manifestRelativePath(outputDir, it) }

    return (orderedShared + wrapperFiles.sortedBy { it.name.lowercase() } + orderedNonShared)
        .map { manifestRelativePath(outputDir, it) }
}

private fun wrapperLibraryName(os: String): String =
    when (os) {
        "windows" -> "ffmpegkitjni.dll"
        "macos" -> "libffmpegkitjni.dylib"
        else -> "libffmpegkitjni.so"
    }

private fun sharedLibraryFamilyName(os: String, baseName: String): String =
    when (os) {
        "windows" -> "$baseName-"
        else -> "lib$baseName"
    }

private fun java.io.File.invariantPathSegments(): List<String> =
    path.replace("\\", "/").split('/')
