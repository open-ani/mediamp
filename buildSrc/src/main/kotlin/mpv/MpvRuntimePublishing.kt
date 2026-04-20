package mpv

import org.gradle.api.Project
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

internal data class PublishedArtifact(
    val artifactNotation: Any,
    val builtBy: Any? = null,
)

internal fun registerDesktopRuntimeJarTasks(
    context: MpvBuildContext,
): Map<DesktopRuntimeTarget, PublishedArtifact> {
    val runtimeJarTasks = linkedMapOf<DesktopRuntimeTarget, PublishedArtifact>()

    context.desktopRuntimeTargets.forEach { target ->
        val taskName = "mpvRuntimeJar${target.os.replaceFirstChar { it.uppercase() }}${target.arch.replaceFirstChar { it.uppercase() }}"
        val assembleTaskName = "mpvAssemble${target.mpvTargetName}"
        if (context.project.tasks.names.contains(assembleTaskName)) {
            val outputDir = context.project.layout.buildDirectory.dir("mpv-output/${target.mpvTargetName}")

            val jarTask = context.project.tasks.register<Jar>(taskName) {
                group = "mpv"
                description = "Package mpv runtime for ${target.os}-${target.arch}"
                archiveBaseName.set("mediamp-mpv-runtime")
                archiveClassifier.set("${target.os}-${target.arch}")
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
            "prebuilt-runtime-jars/${target.os}-${target.arch}/mediamp-mpv-runtime-${context.project.version}-${target.os}-${target.arch}.jar",
        ).get().asFile
        if (prebuiltJar.isFile) {
            context.project.logger.lifecycle(
                "Using prebuilt mpv runtime jar for ${target.os}-${target.arch}: ${prebuiltJar.absolutePath}",
            )
            runtimeJarTasks[target] = PublishedArtifact(prebuiltJar)
            return@forEach
        }

        context.project.logger.lifecycle(
            "Skipping mpv runtime publication task for ${target.os}-${target.arch}: neither $assembleTaskName nor ${prebuiltJar.absolutePath} is available.",
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
            create<MavenPublication>("mpvRuntime${target.os.replaceFirstChar { it.uppercase() }}${target.arch.replaceFirstChar { it.uppercase() }}") {
                groupId = "org.openani.mediamp"
                artifactId = "mediamp-mpv-runtime-${target.os}-${target.arch}"
                version = deployVersion

                artifact(jarTask.artifactNotation) {
                    classifier = null
                    jarTask.builtBy?.let { builtBy(it) }
                }

                addMainModulePomDependency(deployVersion)
            }
        }
    }

    desktopRuntimeJarTasks.forEach { (target, jarTask) ->
        registerCompositeRuntimeElements(
            context = context,
            configurationName = "mpvCompositeRuntimeElements-${target.os}-${target.arch}",
            moduleName = "mediamp-mpv-runtime-${target.os}-${target.arch}",
            runtimeJarArtifact = jarTask,
        ) {
            attributes.attribute(Usage.USAGE_ATTRIBUTE, context.project.objects.named<Usage>(Usage.JAVA_RUNTIME))
            attributes.attribute(Category.CATEGORY_ATTRIBUTE, context.project.objects.named<Category>(Category.LIBRARY))
            attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, context.project.objects.named<Bundling>(Bundling.EXTERNAL))
            attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, context.project.objects.named<LibraryElements>(LibraryElements.JAR))
            attributes.attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
            attributes.attribute(
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                context.project.objects.named<TargetJvmEnvironment>(TargetJvmEnvironment.STANDARD_JVM),
            )
            attributes.attribute(
                OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE,
                context.project.objects.named<OperatingSystemFamily>(
                    when (target.os) {
                        "linux" -> OperatingSystemFamily.LINUX
                        "windows" -> OperatingSystemFamily.WINDOWS
                        "macos" -> OperatingSystemFamily.MACOS
                        else -> error("Unknown OS: ${target.os}")
                    },
                ),
            )
            attributes.attribute(
                MachineArchitecture.ARCHITECTURE_ATTRIBUTE,
                context.project.objects.named<MachineArchitecture>(
                    when (target.arch) {
                        "x64" -> MachineArchitecture.X86_64
                        "arm64" -> MachineArchitecture.ARM64
                        else -> error("Unknown arch: ${target.arch}")
                    },
                ),
            )
        }
    }

    val allRuntimeVariants = runtimeTargets.map { target ->
        context.project.configurations.create("mpvRuntimeElements-${target.os}-${target.arch}").apply {
            attributes.attribute(Usage.USAGE_ATTRIBUTE, context.project.objects.named<Usage>(Usage.JAVA_RUNTIME))
            attributes.attribute(Category.CATEGORY_ATTRIBUTE, context.project.objects.named<Category>(Category.LIBRARY))
            attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, context.project.objects.named<Bundling>(Bundling.EXTERNAL))
            attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, context.project.objects.named<LibraryElements>(LibraryElements.JAR))
            attributes.attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
            attributes.attribute(
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                context.project.objects.named<TargetJvmEnvironment>(TargetJvmEnvironment.STANDARD_JVM),
            )
            attributes.attribute(
                OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE,
                context.project.objects.named<OperatingSystemFamily>(
                    when (target.os) {
                        "linux" -> OperatingSystemFamily.LINUX
                        "windows" -> OperatingSystemFamily.WINDOWS
                        "macos" -> OperatingSystemFamily.MACOS
                        else -> error("Unknown OS: ${target.os}")
                    },
                ),
            )
            attributes.attribute(
                MachineArchitecture.ARCHITECTURE_ATTRIBUTE,
                context.project.objects.named<MachineArchitecture>(
                    when (target.arch) {
                        "x64" -> MachineArchitecture.X86_64
                        "arm64" -> MachineArchitecture.ARM64
                        else -> error("Unknown arch: ${target.arch}")
                    },
                ),
            )
            dependencies.add(
                context.project.dependencies.create(
                    "org.openani.mediamp:mediamp-mpv-runtime-${target.os}-${target.arch}:$deployVersion",
                ),
            )
        }
    }

    if (allRuntimeVariants.isNotEmpty()) {
        val runtimeComponent = context.project.serviceOf<SoftwareComponentFactory>().adhoc("mpvRuntimeElements")
        allRuntimeVariants.forEach { variant ->
            runtimeComponent.addVariantsFromConfiguration(variant) {
                mapToMavenScope("runtime")
            }
        }

        context.project.extensions.getByType<PublishingExtension>().publications.create<MavenPublication>("mpvRuntime") {
            from(runtimeComponent)
            groupId = "org.openani.mediamp"
            artifactId = "mediamp-mpv-runtime"
            version = deployVersion
            pom.withXml {
                val deps = asElement().getElementsByTagName("dependencies")
                for (index in 0 until deps.length) {
                    deps.item(index).parentNode.removeChild(deps.item(index))
                }
            }
        }
    }

    wireDesktopRuntimeDependencyConstraints(context.project, deployVersion, runtimeTargets)
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

private fun registerCompositeRuntimeElements(
    context: MpvBuildContext,
    configurationName: String,
    moduleName: String,
    runtimeJarArtifact: PublishedArtifact,
    configureAttributes: org.gradle.api.artifacts.Configuration.() -> Unit,
) {
    context.project.configurations.create(configurationName).apply {
        isCanBeConsumed = true
        isCanBeResolved = false
        configureAttributes()

        outgoing.artifact(runtimeJarArtifact.artifactNotation) {
            runtimeJarArtifact.builtBy?.let { builtBy(it) }
        }
        outgoing.capability("org.openani.mediamp:$moduleName:${context.project.version}")
    }
}

private fun MavenPublication.addMainModulePomDependency(deployVersion: String) {
    pom.withXml {
        asNode().appendNode("dependencies")
            .appendNode("dependency").apply {
                appendNode("groupId", "org.openani.mediamp")
                appendNode("artifactId", "mediamp-mpv")
                appendNode("version", "[$deployVersion]")
                appendNode("scope", "compile")
            }
    }
}

private fun wireDesktopRuntimeDependencyConstraints(
    project: Project,
    deployVersion: String,
    runtimeTargets: List<DesktopRuntimeTarget>,
) {
    val desktopPlatformRuntimeNotations = runtimeTargets.map { target ->
        "org.openani.mediamp:mediamp-mpv-runtime-${target.os}-${target.arch}:$deployVersion"
    }

    project.afterEvaluate {
        listOf("desktopApiElements", "desktopRuntimeElements").forEach { configName ->
            configurations.findByName(configName)?.let { config ->
                desktopPlatformRuntimeNotations.forEach { notation ->
                    config.dependencyConstraints.add(dependencies.constraints.create("$notation!!"))
                }
            }
        }
    }
}
