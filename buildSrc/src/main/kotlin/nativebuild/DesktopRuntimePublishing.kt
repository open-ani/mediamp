package nativebuild

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

internal data class PublishedArtifact(
    val artifactNotation: Any,
    val builtBy: Any? = null,
)

internal fun Project.registerCompositeDesktopRuntimeElements(
    configurationName: String,
    moduleName: String,
    runtimeJarArtifact: PublishedArtifact,
    target: DesktopRuntimeTarget,
): Configuration =
    configurations.create(configurationName).apply {
        isCanBeConsumed = true
        isCanBeResolved = false
        configureDesktopRuntimeAttributes(this, target)

        outgoing.artifact(runtimeJarArtifact.artifactNotation) {
            runtimeJarArtifact.builtBy?.let { builtBy(it) }
        }
        outgoing.capability("org.openani.mediamp:$moduleName:$version")
    }

internal fun Project.createDependencyOnlyDesktopRuntimeElements(
    configurationName: String,
    dependencyNotation: String,
    target: DesktopRuntimeTarget,
): Configuration =
    configurations.create(configurationName).apply {
        configureDesktopRuntimeAttributes(this, target)
        dependencies.add(this@createDependencyOnlyDesktopRuntimeElements.dependencies.create(dependencyNotation))
    }

internal fun Project.publishDesktopRuntimeAggregator(
    componentName: String,
    publicationName: String,
    groupId: String,
    artifactId: String,
    version: String,
    variantConfigurations: Iterable<Configuration>,
) {
    val variants = variantConfigurations.toList()
    if (variants.isEmpty()) return

    val runtimeComponent = serviceOf<SoftwareComponentFactory>().adhoc(componentName)
    variants.forEach { variant ->
        runtimeComponent.addVariantsFromConfiguration(variant) {
            mapToMavenScope("runtime")
        }
    }

    extensions.getByType<PublishingExtension>().publications.create<MavenPublication>(publicationName) {
        from(runtimeComponent)
        this.groupId = groupId
        this.artifactId = artifactId
        this.version = version
        stripPomDependencies()
    }
}

internal fun MavenPublication.addCompilePomDependency(
    groupId: String,
    artifactId: String,
    version: String,
) {
    pom.withXml {
        asNode().appendNode("dependencies")
            .appendNode("dependency").apply {
                appendNode("groupId", groupId)
                appendNode("artifactId", artifactId)
                appendNode("version", "[$version]")
                appendNode("scope", "compile")
            }
    }
}

internal fun Project.wireDesktopRuntimeDependencyConstraints(
    dependencyNotations: List<String>,
    configurationNames: List<String> = listOf("desktopApiElements", "desktopRuntimeElements"),
) {
    afterEvaluate {
        configurationNames.forEach { configName ->
            configurations.findByName(configName)?.let { config ->
                dependencyNotations.forEach { notation ->
                    config.dependencyConstraints.add(dependencies.constraints.create("$notation!!"))
                }
            }
        }
    }
}

private fun Project.configureDesktopRuntimeAttributes(
    configuration: Configuration,
    target: DesktopRuntimeTarget,
) {
    configuration.attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named<Usage>(Usage.JAVA_RUNTIME))
    configuration.attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named<Category>(Category.LIBRARY))
    configuration.attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named<Bundling>(Bundling.EXTERNAL))
    configuration.attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named<LibraryElements>(LibraryElements.JAR))
    configuration.attributes.attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
    configuration.attributes.attribute(
        TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
        objects.named<TargetJvmEnvironment>(TargetJvmEnvironment.STANDARD_JVM),
    )
    configuration.attributes.attribute(
        OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE,
        objects.named<OperatingSystemFamily>(
            when (target.os) {
                "linux" -> OperatingSystemFamily.LINUX
                "windows" -> OperatingSystemFamily.WINDOWS
                "macos" -> OperatingSystemFamily.MACOS
                else -> error("Unknown OS: ${target.os}")
            },
        ),
    )
    configuration.attributes.attribute(
        MachineArchitecture.ARCHITECTURE_ATTRIBUTE,
        objects.named<MachineArchitecture>(
            when (target.arch) {
                "x64" -> MachineArchitecture.X86_64
                "arm64" -> MachineArchitecture.ARM64
                else -> error("Unknown arch: ${target.arch}")
            },
        ),
    )
}

private fun MavenPublication.stripPomDependencies() {
    pom.withXml {
        val deps = asElement().getElementsByTagName("dependencies")
        for (index in 0 until deps.length) {
            deps.item(index).parentNode.removeChild(deps.item(index))
        }
    }
}
