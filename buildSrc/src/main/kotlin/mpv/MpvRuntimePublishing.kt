package mpv

import nativebuild.DesktopRuntimeTarget
import nativebuild.PublishedArtifact
import nativebuild.addCompilePomDependency
import nativebuild.artifactSuffix
import nativebuild.createDependencyOnlyJvmRuntimeElements
import nativebuild.isSharedRuntimeLibrary
import nativebuild.manifestRelativePath
import nativebuild.orderLibrariesByPrefixes
import nativebuild.orderWindowsDllsByDependencies
import nativebuild.publicationSuffix
import nativebuild.publishDesktopRuntimeAggregator
import nativebuild.registerCompositeDesktopRuntimeElements
import nativebuild.resolveMsys2Dir
import nativebuild.resolveWindowsObjdump
import nativebuild.wireDesktopRuntimeDependencyConstraints
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.process.ExecOperations

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
                    val runtimeFiles = runtimeManifestEntries(
                        outputDir = outputDir.get().asFile,
                        target = target,
                        execOperations = context.project.serviceOf<ExecOperations>(),
                        msys2Dir = if (target.os == "windows") context.project.resolveMsys2Dir() else null,
                    )
                    // 平台化命名: 多个平台的 runtime jar 可同时出现在 classpath 上 (聚合工件),
                    // loader 按当前 os-arch 取自己的清单, 互不冲突.
                    val manifestFile = temporaryDir.resolve("mpv-natives-${target.artifactSuffix()}.txt")
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

    // 聚合工件 mediamp-mpv-runtime: 单一 JVM runtime variant, 依赖本次构建可发布的全部平台
    // runtime jar. 之前的属性化 per-OS variant 对普通 JVM 消费端会歧义 (consumer 不携带
    // OS/arch 属性); fat 聚合零配置可用: runtimeOnly("org.openani.mediamp:mediamp-mpv-runtime")
    // 一行全平台通用 (loader 按平台化清单名取对应 natives). 关心分发体积的应用仍可按平台
    // 声明 mediamp-mpv-runtime-<os>-<arch>.
    if (desktopRuntimeJarTasks.isNotEmpty()) {
        val fatRuntimeVariant = context.project.createDependencyOnlyJvmRuntimeElements(
            configurationName = "mpvRuntimeAllElements",
            dependencyNotations = desktopRuntimeJarTasks.keys.map { target ->
                "org.openani.mediamp:mediamp-mpv-runtime-${target.artifactSuffix()}:$deployVersion"
            },
            capabilityNotation = "org.openani.mediamp:mediamp-mpv-runtime:$deployVersion",
        )

        context.project.publishDesktopRuntimeAggregator(
            componentName = "mpvRuntimeElements",
            publicationName = "mpvRuntime",
            groupId = "org.openani.mediamp",
            artifactId = "mediamp-mpv-runtime",
            version = deployVersion,
            variantConfigurations = listOf(fatRuntimeVariant),
        )
    }

    context.project.wireDesktopRuntimeDependencyConstraints(
        runtimeTargets.map { target ->
            "org.openani.mediamp:mediamp-mpv-runtime-${target.artifactSuffix()}:$deployVersion"
        },
    )
}

private fun runtimeManifestEntries(
    outputDir: java.io.File,
    target: DesktopRuntimeTarget,
    execOperations: ExecOperations,
    msys2Dir: java.io.File?,
): List<String> {
    val runtimeRoot = when (target.os) {
        "windows" -> outputDir.resolve("bin")
        "linux", "macos" -> outputDir.resolve("lib")
        else -> error("Unknown desktop runtime OS: ${target.os}")
    }
    val runtimeFiles = runtimeRoot.listFiles()
        ?.filter { candidate -> candidate.isFile && isSharedRuntimeLibrary(candidate, target.os) }
        .orEmpty()
    val wrapperFiles = runtimeFiles.filter { candidate -> candidate.name.equals(wrapperLibraryName(target.os), ignoreCase = true) }
    val dependencyLibraries = runtimeFiles - wrapperFiles.toSet()

    val orderedShared = when (target.os) {
        "windows" -> {
            val msys2Root = msys2Dir ?: error("MSYS2 directory must be available when packaging Windows mpv runtimes.")
            orderWindowsDllsByDependencies(
                execOperations = execOperations,
                objdumpExecutable = resolveWindowsObjdump(msys2Root, windowsMsysBinDir(target)),
                dllFiles = dependencyLibraries,
            )
        }

        else -> orderLibrariesByPrefixes(
            files = dependencyLibraries,
            orderedPrefixes = listOf(
                "libavutil",
                "libswresample",
                "libswscale",
                "libavcodec",
                "libavformat",
                "libavfilter",
                "libavdevice",
                "libass",
                "libplacebo",
                "libmpv",
            ),
            unmatchedFirst = true,
        )
    }

    return (orderedShared + wrapperFiles.sortedBy { it.name.lowercase() })
        .map { manifestRelativePath(runtimeRoot, it) }
}

private fun windowsMsysBinDir(target: DesktopRuntimeTarget): String =
    if (target.targetName == "WindowsArm64") "clangarm64/bin" else "ucrt64/bin"

private fun wrapperLibraryName(os: String): String =
    when (os) {
        "windows" -> "mediampv.dll"
        "macos" -> "libmediampv.dylib"
        else -> "libmediampv.so"
    }
