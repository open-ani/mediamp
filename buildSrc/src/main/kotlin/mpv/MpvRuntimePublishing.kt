package mpv

import nativebuild.DesktopRuntimeTarget
import nativebuild.PublishedArtifact
import nativebuild.addCompilePomDependency
import nativebuild.artifactSuffix
import nativebuild.createDependencyOnlyDesktopRuntimeElements
import nativebuild.publicationSuffix
import nativebuild.publishDesktopRuntimeAggregator
import nativebuild.registerCompositeDesktopRuntimeElements
import nativebuild.wireDesktopRuntimeDependencyConstraints
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

internal fun registerDesktopRuntimeJarTasks(
    context: MpvBuildContext,
): Map<DesktopRuntimeTarget, PublishedArtifact> {
    val runtimeJarTasks = linkedMapOf<DesktopRuntimeTarget, PublishedArtifact>()

    context.desktopRuntimeTargets.forEach { target ->
        val taskName = "mpvRuntimeJar${target.publicationSuffix()}"
        val assembleTaskName = "mpvAssemble${target.targetName}"
        if (context.project.tasks.names.contains(assembleTaskName)) {
            val outputDir = context.project.layout.buildDirectory.dir("mpv-output/${target.targetName}")

            val jarTask = context.project.tasks.register<Jar>(taskName) {
                group = "mpv"
                description = "Package mpv runtime for ${target.artifactSuffix()}"
                archiveBaseName.set("mediamp-mpv-runtime")
                archiveClassifier.set(target.artifactSuffix())
                dependsOn(assembleTaskName)

                when (target.os) {
                    "windows" -> {
                        from(outputDir.map { it.dir("bin") }) {
                            include("*.dll")
                        }
                    }

                    "linux" -> {
                        from(outputDir.map { it.dir("lib") }) {
                            include("*.so", "*.so.*")
                            exclude("*.a", "*.la", "pkgconfig/**", "cmake/**")
                        }
                    }

                    "macos" -> {
                        from(outputDir.map { it.dir("lib") }) {
                            include("*.dylib")
                            exclude("*.a", "pkgconfig/**", "cmake/**")
                        }
                    }

                    else -> error("Unknown desktop runtime OS: ${target.os}")
                }

                doFirst {
                    val runtimeFiles = runtimeManifestEntries(outputDir.get().asFile, target)
                    val manifestFile = temporaryDir.resolve("mpv-natives.txt")
                    manifestFile.parentFile.mkdirs()
                    manifestFile.writeText(runtimeFiles.joinToString("\n"))
                    from(manifestFile)
                }
            }
            runtimeJarTasks[target] = PublishedArtifact(jarTask.flatMap { it.archiveFile }, jarTask)
            return@forEach
        }

        val prebuiltJar = context.project.layout.buildDirectory.file(
            "prebuilt-runtime-jars/${target.artifactSuffix()}/mediamp-mpv-runtime-${context.project.version}-${target.artifactSuffix()}.jar",
        ).get().asFile
        if (prebuiltJar.isFile) {
            context.project.logger.lifecycle(
                "Using prebuilt mpv runtime jar for ${target.artifactSuffix()}: ${prebuiltJar.absolutePath}",
            )
            runtimeJarTasks[target] = PublishedArtifact(prebuiltJar)
            return@forEach
        }

        context.project.logger.lifecycle(
            "Skipping mpv runtime publication task for ${target.artifactSuffix()}: neither $assembleTaskName nor ${prebuiltJar.absolutePath} is available.",
        )
    }

    return runtimeJarTasks
}

internal fun configureRuntimePublishing(
    context: MpvBuildContext,
    desktopRuntimeJarTasks: Map<DesktopRuntimeTarget, PublishedArtifact>,
) {
    val deployVersion = context.project.version.toString()
    val runtimeTargets = context.desktopRuntimeTargets

    context.project.extensions.getByType<PublishingExtension>().publications.apply {
        desktopRuntimeJarTasks.forEach { (target, jarTask) ->
            create<MavenPublication>("mpvRuntime${target.publicationSuffix()}") {
                groupId = "org.openani.mediamp"
                artifactId = "mediamp-mpv-runtime-${target.artifactSuffix()}"
                version = deployVersion

                artifact(jarTask.artifactNotation) {
                    classifier = null
                    jarTask.builtBy?.let { builtBy(it) }
                }

                addCompilePomDependency(
                    groupId = "org.openani.mediamp",
                    artifactId = "mediamp-mpv",
                    version = deployVersion,
                )
            }
        }
    }

    desktopRuntimeJarTasks.forEach { (target, jarTask) ->
        context.project.registerCompositeDesktopRuntimeElements(
            configurationName = "mpvCompositeRuntimeElements-${target.artifactSuffix()}",
            moduleName = "mediamp-mpv-runtime-${target.artifactSuffix()}",
            runtimeJarArtifact = jarTask,
            target = target,
        )
    }

    val allRuntimeVariants = runtimeTargets.map { target ->
        context.project.createDependencyOnlyDesktopRuntimeElements(
            configurationName = "mpvRuntimeElements-${target.artifactSuffix()}",
            dependencyNotation = "org.openani.mediamp:mediamp-mpv-runtime-${target.artifactSuffix()}:$deployVersion",
            target = target,
        )
    }

    context.project.publishDesktopRuntimeAggregator(
        componentName = "mpvRuntimeElements",
        publicationName = "mpvRuntime",
        groupId = "org.openani.mediamp",
        artifactId = "mediamp-mpv-runtime",
        version = deployVersion,
        variantConfigurations = allRuntimeVariants,
    )

    context.project.wireDesktopRuntimeDependencyConstraints(
        runtimeTargets.map { target ->
            "org.openani.mediamp:mediamp-mpv-runtime-${target.artifactSuffix()}:$deployVersion"
        },
    )
}

private fun runtimeManifestEntries(
    outputDir: java.io.File,
    target: DesktopRuntimeTarget,
): List<String> = when (target.os) {
    "windows" -> outputDir.resolve("bin").listFiles()
        ?.filter { it.isFile && it.extension.equals("dll", ignoreCase = true) }
        ?.map { it.name }
        .orEmpty()

    "linux" -> outputDir.resolve("lib").listFiles()
        ?.filter { it.isFile && (it.name.endsWith(".so") || ".so." in it.name) }
        ?.map { it.name }
        .orEmpty()

    "macos" -> outputDir.resolve("lib").listFiles()
        ?.filter { it.isFile && it.name.endsWith(".dylib") }
        ?.map { it.name }
        .orEmpty()

    else -> error("Unknown desktop runtime OS: ${target.os}")
}.sorted()
