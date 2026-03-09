package ffmpeg

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
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
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily
import org.gradle.api.tasks.TaskProvider

internal fun registerDesktopRuntimeJarTasks(
    context: FfmpegBuildContext,
): Map<DesktopRuntimeTarget, TaskProvider<Jar>> {
    val runtimeJarTasks = linkedMapOf<DesktopRuntimeTarget, TaskProvider<Jar>>()

    context.desktopRuntimeTargets.forEach { target ->
        val taskName = "ffmpegRuntimeJar${target.os.replaceFirstChar { it.uppercase() }}${target.arch.replaceFirstChar { it.uppercase() }}"
        val assembleTaskName = "ffmpegAssemble${target.ffmpegTargetName}"
        if (!context.project.tasks.names.contains(assembleTaskName)) {
            context.project.logger.lifecycle(
                "Skipping runtime publication task for ${target.os}-${target.arch}: $assembleTaskName is not available on this host.",
            )
            return@forEach
        }
        val outputDir = context.project.layout.buildDirectory.dir("ffmpeg-output/${target.ffmpegTargetName}")

        val jarTask = context.project.tasks.register<Jar>(taskName) {
            group = "ffmpeg"
            description = "Package FFmpeg runtime for ${target.os}-${target.arch}"
            archiveBaseName.set("mediamp-ffmpeg-runtime")
            archiveClassifier.set("${target.os}-${target.arch}")
            dependsOn(assembleTaskName)

            from(outputDir) {
                exclude("include/**")
            }

            doFirst {
                val dir = outputDir.get().asFile
                if (dir.isDirectory) {
                    val manifest = dir.walk()
                        .filter { it.isFile && !it.path.contains("include") }
                        .map { it.relativeTo(dir).path.replace("\\", "/") }
                        .sorted()
                        .joinToString("\n")
                    val manifestFile = context.project.layout.buildDirectory.file("tmp/$taskName/ffmpeg-natives.txt").get().asFile
                    manifestFile.parentFile.mkdirs()
                    manifestFile.writeText(manifest)
                    from(manifestFile)
                }
            }
        }
        runtimeJarTasks[target] = jarTask
    }

    return runtimeJarTasks
}

internal fun configureRuntimePublishing(
    context: FfmpegBuildContext,
    runtimeJarTasks: Map<DesktopRuntimeTarget, TaskProvider<Jar>>,
) {
    val deployVersion = context.project.version.toString()
    val publishedTargets = runtimeJarTasks.keys.toList()

    context.project.extensions.getByType<com.vanniktech.maven.publish.MavenPublishBaseExtension>().apply {
        configure(KotlinMultiplatform(JavadocJar.Empty(), SourcesJar.Sources(), listOf("debug", "release")))
        publishToMavenCentral()
        signAllPublicationsIfEnabled(context.project)
        configurePom(context.project)
    }

    context.project.extensions.getByType<PublishingExtension>().publications.apply {
        runtimeJarTasks.forEach { (target, jarTask) ->
            create<MavenPublication>("ffmpegRuntime${target.os.replaceFirstChar { it.uppercase() }}${target.arch.replaceFirstChar { it.uppercase() }}") {
                groupId = "org.openani.mediamp"
                artifactId = "mediamp-ffmpeg-runtime-${target.os}-${target.arch}"
                version = deployVersion

                artifact(jarTask)

                pom.withXml {
                    asNode().appendNode("dependencies")
                        .appendNode("dependency").apply {
                            appendNode("groupId", "org.openani.mediamp")
                            appendNode("artifactId", "mediamp-ffmpeg")
                            appendNode("version", "[$deployVersion]")
                            appendNode("scope", "compile")
                        }
                }
            }
        }
    }

    val allRuntimeVariants = publishedTargets.map { target ->
        context.project.configurations.create("ffmpegRuntimeElements-${target.os}-${target.arch}").apply {
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
                    "org.openani.mediamp:mediamp-ffmpeg-runtime-${target.os}-${target.arch}:$deployVersion",
                ),
            )
        }
    }

    if (allRuntimeVariants.isNotEmpty()) {
        val runtimeComponent = context.project.serviceOf<SoftwareComponentFactory>().adhoc("ffmpegRuntimeElements")
        allRuntimeVariants.forEach { variant ->
            runtimeComponent.addVariantsFromConfiguration(variant) {
                mapToMavenScope("runtime")
            }
        }

        context.project.extensions.getByType<PublishingExtension>().publications.create<MavenPublication>("ffmpegRuntime") {
            from(runtimeComponent)
            groupId = "org.openani.mediamp"
            artifactId = "mediamp-ffmpeg-runtime"
            version = deployVersion
            pom.withXml {
                val deps = asElement().getElementsByTagName("dependencies")
                for (index in 0 until deps.length) {
                    deps.item(index).parentNode.removeChild(deps.item(index))
                }
            }
        }
    } else {
        context.project.logger.lifecycle("Skipping ffmpegRuntime uber publication: no desktop runtime targets are available for this host/buildvariant.")
    }

    wireDesktopRuntimeDependencyConstraints(context.project, deployVersion, publishedTargets)
}

private fun wireDesktopRuntimeDependencyConstraints(
    project: Project,
    deployVersion: String,
    publishedTargets: List<DesktopRuntimeTarget>,
) {
    val desktopUberRuntimeNotation = "org.openani.mediamp:mediamp-ffmpeg-runtime:$deployVersion"
    val desktopPlatformRuntimeNotations = publishedTargets.map { target ->
        "org.openani.mediamp:mediamp-ffmpeg-runtime-${target.os}-${target.arch}:$deployVersion"
    }

    project.afterEvaluate {
        if (publishedTargets.isEmpty()) {
            logger.lifecycle("Skipping desktop runtime dependency wiring: no published desktop runtime targets for this host/buildvariant.")
            return@afterEvaluate
        }

        configurations.findByName("desktopRuntimeElements")?.dependencies?.add(
            dependencies.create(desktopUberRuntimeNotation),
        )

        listOf("desktopApiElements", "desktopRuntimeElements").forEach { configName ->
            configurations.findByName(configName)?.let { config ->
                config.dependencyConstraints.add(dependencies.constraints.create("$desktopUberRuntimeNotation!!"))
                desktopPlatformRuntimeNotations.forEach { notation ->
                    config.dependencyConstraints.add(dependencies.constraints.create("$notation!!"))
                }
            }
        }
    }
}

private fun MavenPublishBaseExtension.signAllPublicationsIfEnabled(project: Project) {
    if (project.findProperty("mediamp.sign.publications.disabled")?.toString()?.toBoolean() == true) return
    if (!project.hasSigningCredentials()) {
        project.logger.lifecycle("Skipping publication signing: no Gradle signing credentials are configured.")
        return
    }
    signAllPublications()
}

private fun Project.hasSigningCredentials(): Boolean {
    fun prop(name: String): String? = findProperty(name)?.toString()?.takeIf { it.isNotBlank() }

    val inMemoryKey = prop("signingInMemoryKey")
    val inMemoryPassword = prop("signingInMemoryKeyPassword")
    val legacyKey = prop("signingKey")
    val legacyPassword = prop("signingPassword")
    val keyRing = prop("signing.secretKeyRingFile")
    val signingPassword = prop("signing.password")

    return (inMemoryKey != null && inMemoryPassword != null) ||
        (legacyKey != null && legacyPassword != null) ||
        (keyRing != null && signingPassword != null)
}

private fun MavenPublishBaseExtension.configurePom(project: Project) {
    pom {
        name.set(project.name)
        description.set(project.description)
        url.set("https://github.com/open-ani/mediamp")

        licenses {
            license {
                name.set("GNU General Public License, Version 3")
                url.set("https://github.com/open-ani/mediamp/blob/main/LICENSE")
                distribution.set("https://www.gnu.org/licenses/gpl-3.0.txt")
            }
        }

        developers {
            developer {
                id.set("openani")
                name.set("OpenAni and contributors")
                email.set("support@openani.org")
            }
        }

        scm {
            connection.set("scm:git:https://github.com/open-ani/mediamp.git")
            developerConnection.set("scm:git:git@github.com:open-ani/mediamp.git")
            url.set("https://github.com/open-ani/mediamp")
        }
    }
}
