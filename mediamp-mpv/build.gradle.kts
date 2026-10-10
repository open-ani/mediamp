/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import mpv.configureMediampMpvModule
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import java.util.Properties

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")

    kotlin("plugin.compose")
    id("org.jetbrains.compose")

    `mpp-lib-targets`
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
    idea
}

description = "MediaMP backend using MPV"

kotlin {
    androidLibrary {
        namespace = "org.openani.mediamp.mpv"
    }
    
    sourceSets {
//        androidMain {
//            kotlin.srcDirs(listOf("gen/java"))
//        }
        commonMain {
            dependencies {
                api(projects.mediampApi)
                implementation(projects.mediampInternalUtils)
            }
        }
        getByName("jvmMain").dependencies {
            implementation(projects.mediampNativeLoader)
        }
        getByName("desktopMain").dependencies {
            // The v2 state machine is confined to Dispatchers.Main; on desktop JVM the Main
            // dispatcher is provided by coroutines-swing (Swing EDT). Ship it so consumers
            // do not crash with "Module with the Main dispatcher is missing".
            api(libs.kotlinx.coroutines.swing)
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            // The live-redrawer regression test constructs a real SkiaLayer.
            runtimeOnly(compose.desktop.currentOs)
        }
    }
}

configureMediampMpvModule()

// Dev-only fast path (macOS): compile the JNI wrapper against a system (Homebrew) libmpv
// so the module can be developed/tested without the full meson mpv build. The output
// directory is meant for MpvMediampPlayer.prepareLibraries(dir, extractRuntimeLibrary = false).
val devNativeDir = layout.buildDirectory.dir("dev-native")
val devMpvPrefix = getPropertyOrNull("mediamp.mpv.dev.prefix") ?: "/opt/homebrew"
val compileJniDevMacos = tasks.register<Exec>("compileJniDevMacos") {
    group = "mediamp"
    description = "Compile libmediampv.dylib against Homebrew libmpv (macOS dev only)"
    // CI runners don't have Homebrew mpv headers; the real runtime is built via meson
    // (mpvAssemble*) there, so silently skip this dev convenience when headers are absent.
    onlyIf { getOs() == Os.MacOS && file("$devMpvPrefix/include/mpv/client.h").isFile }
    val srcDir = layout.projectDirectory.dir("src/cpp")
    val outputFile = devNativeDir.map { it.file("libmediampv.dylib") }
    inputs.dir(srcDir)
    outputs.file(outputFile)
    val mpvPrefix = devMpvPrefix
    commandLine(
        "/bin/zsh", "-c",
        buildString {
            append("mkdir -p \"\$(dirname ${outputFile.get().asFile.absolutePath})\" && ")
            append("JAVA_HOME=\"\${JAVA_HOME:-\$(/usr/libexec/java_home)}\" && ")
            append("clang++ -std=c++17 -fPIC -fobjc-arc -O2 -dynamiclib ")
            append("-I ${srcDir.asFile.absolutePath}/include ")
            append("-I \"\$JAVA_HOME/include\" -I \"\$JAVA_HOME/include/darwin\" ")
            append("-I $mpvPrefix/include -L $mpvPrefix/lib -lmpv -lavcodec ")
            append("-framework Foundation -framework Metal -framework IOSurface ")
            append("-framework OpenGL -framework QuartzCore -framework CoreGraphics -framework ImageIO ")
            append("${srcDir.asFile.absolutePath}/*.cpp ${srcDir.asFile.absolutePath}/*.mm ")
            append("-o ${outputFile.get().asFile.absolutePath}")
        },
    )
}

val hostMpvTargetName = when (getOs()) {
    Os.Windows -> if (getArch() == Arch.AARCH64) "WindowsArm64" else "WindowsX64"
    Os.Linux -> "LinuxX64"
    Os.MacOS -> if (getArch() == Arch.AARCH64) "MacosArm64" else "MacosX64"
    else -> null
}
val hostMpvAssembleTaskName = hostMpvTargetName?.let { "mpvAssemble$it" }

tasks.withType<Test>().configureEach {
    // Where MpvMediampPlayerSmokeTest loads the native runtime from:
    // - Windows: the assembled meson runtime (no system libmpv exists, and the D3D11
    //     render API needs our patched build anyway) — mpv-output/<target>/bin.
    // - macOS on a required runner (self-hosted, full environment): the assembled meson
    //     runtime — mpv-output/<target>/lib, which includes libmediampv.dylib. That
    //     runner has no Homebrew libmpv, so the dev fast-path below would skip and, under
    //     required mode, fail; and it is the one place that builds the real runtime, so
    //     the smoke test should exercise exactly what ships.
    // - macOS local dev: the JNI wrapper compiled against Homebrew libmpv (fast loop,
    //     no full meson build required).
    val mpvTestRequired = getPropertyOrNull("mediamp.mpv.test.required") == "true"
    val testNativeDir = when {
        getOs() == Os.Windows -> {
            if (mpvTestRequired) {
                hostMpvAssembleTaskName?.let { dependsOn(it) }
            }
            layout.buildDirectory.dir("mpv-output/$hostMpvTargetName/bin").get().asFile
        }

        getOs() == Os.MacOS && mpvTestRequired -> {
            val macosTarget = if (getArch() == Arch.AARCH64) "MacosArm64" else "MacosX64"
            dependsOn("mpvAssemble$macosTarget")
            layout.buildDirectory.dir("mpv-output/$macosTarget/lib").get().asFile
        }

        else -> {
            dependsOn(compileJniDevMacos)
            devNativeDir.get().asFile
        }
    }
    systemProperty("mediamp.mpv.dev.native.dir", testNativeDir.absolutePath)
    // CI 上防静默跳过: -Pmediamp.mpv.test.required=true 时环境缺失会 fail 而不是 skip
    systemProperty("mediamp.mpv.test.required", getPropertyOrNull("mediamp.mpv.test.required") ?: "false")
}

val hostMpvOutputDir = hostMpvTargetName?.let { layout.buildDirectory.dir("mpv-output/$it") }
val legacyNativeBuildDir = projectDir.resolve("build-ci")

val nativeJarForCurrentPlatform = tasks.register("nativeJarForCurrentPlatform", Jar::class.java) {
    group = "mediamp"
    description = "Create a jar for the native files for current platform"
    archiveClassifier.set(getOsTriple())
    isEnabled = hostMpvTargetName != null

    hostMpvAssembleTaskName?.let { dependsOn(it) }

    when (getOs()) {
        Os.Linux -> {
            hostMpvOutputDir?.let { outputDir ->
                from(outputDir.map { it.dir("lib") }) {
                    include("*.so", "*.so.*")
                    exclude("*.a", "*.la", "pkgconfig/**", "cmake/**")
                }
            }
        }

        Os.MacOS -> {
            hostMpvOutputDir?.let { outputDir ->
                from(outputDir.map { it.dir("lib") }) {
                    include("*.dylib")
                    exclude("*.a", "pkgconfig/**", "cmake/**")
                }
            }
        }

        Os.Windows -> {
            hostMpvOutputDir?.let { outputDir ->
                from(outputDir.map { it.dir("bin") }) {
                    include("*.dll")
                }
            }
        }

        else -> {}
    }
}

val nativeJarsDir = layout.buildDirectory.dir("native-jars")
val copyNativeJarForCurrentPlatform = tasks.register("copyNativeJarForCurrentPlatform", Copy::class.java) {
    dependsOn(nativeJarForCurrentPlatform)
    description = "Copy native jar for current platform"
    group = "mediamp"
    from(nativeJarForCurrentPlatform.flatMap { it.archiveFile })
    into(nativeJarsDir)
}

tasks.named("assemble") {
    dependsOn(copyNativeJarForCurrentPlatform)
}

// In-repo zero-config verification: runs MpvZeroConfigTest in a fresh JVM with the platform
// runtime jar (natives + per-platform manifest) on the classpath and WITHOUT prepareLibraries —
// exactly the contract consumers get from runtimeOnly("org.openani.mediamp:mediamp-mpv-runtime").
// A separate Test task because the native loader keeps global state per JVM.
val hostMpvRuntimeJarTaskName = hostMpvTargetName?.let { "mpvRuntimeJar$it" }
if (hostMpvRuntimeJarTaskName != null && hostMpvRuntimeJarTaskName in tasks.names) {
    tasks.register<Test>("zeroConfigTest") {
        group = "mediamp"
        description = "Verifies zero-config native loading with the mpv runtime jar on the classpath"
        val testCompilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
        testClassesDirs = testCompilation.output.classesDirs
        classpath = files(
            testCompilation.output.allOutputs,
            testCompilation.runtimeDependencyFiles,
            tasks.named<org.gradle.jvm.tasks.Jar>(hostMpvRuntimeJarTaskName).flatMap { it.archiveFile },
        )
        filter { includeTestsMatching("*ZeroConfig*") }
        systemProperty("mediamp.mpv.zeroconfig", "true")
    }
}

// Render path matrix: RenderPathMatrixTest once per Skiko render API, each in a fresh JVM
// because Skiko reads skiko.renderApi when the window is created and the native loader keeps
// per-JVM state. An API the machine cannot provide still runs: Skiko falls back as it would
// for a user and the cell reports where it landed (-Pmediamp.mpv.renderpath.strict=true makes
// that a failure). Results: build/render-path-matrix/<API>/.
// ./gradlew --continue :mediamp-mpv:renderPathMatrix
val renderPathApis = when (getOs()) {
    // Skiko has no OpenGL renderer on Windows ARM64.
    Os.Windows -> if (getArch() == Arch.AARCH64) {
        listOf("DIRECT3D", "ANGLE", "SOFTWARE_FAST", "SOFTWARE_COMPAT")
    } else {
        listOf("DIRECT3D", "ANGLE", "OPENGL", "SOFTWARE_FAST", "SOFTWARE_COMPAT")
    }
    Os.MacOS -> listOf("METAL", "SOFTWARE_COMPAT")
    else -> emptyList()
}
val renderPathMatrixDir = layout.buildDirectory.dir("render-path-matrix")

tasks.named<Test>("desktopTest") {
    filter { excludeTestsMatching("*RenderPathMatrixTest") }
}

val renderPathMatrixReport = tasks.register("renderPathMatrixReport") {
    group = "mediamp"
    description = "Tabulates the render path matrix results, also into the GitHub step summary"
    val resultsDir = renderPathMatrixDir.get().asFile
    val apis = renderPathApis
    // Results left over from an earlier build (a cell not run this time) are not reported.
    val buildStartMillis = System.currentTimeMillis()
    doLast {
        fun cell(value: String?) = (value ?: "").replace("|", "\\|").replace("\n", " ")
        val report = buildString {
            appendLine("### Render path matrix (${System.getProperty("os.name")} ${System.getProperty("os.arch")})")
            appendLine()
            appendLine("| Requested | Skiko actual | Redrawer | mediamp path | hwdec | VO drops | Result |")
            appendLine("|---|---|---|---|---|---|---|")
            val details = StringBuilder()
            for (api in apis) {
                val file = resultsDir.resolve("$api/result.properties")
                if (!file.isFile || file.lastModified() < buildStartMillis) {
                    appendLine("| $api | | | | | | not run |")
                    continue
                }
                val result = Properties().apply { file.reader().use { load(it) } }
                fun get(key: String) = cell(result.getProperty(key))
                appendLine(
                    "| $api | ${get("actualApi")} | ${get("redrawer")} | ${get("mediampRenderer")} | " +
                        "${get("hwdec")} | ${get("droppedFrames")} | ${get("result")} |",
                )
                val failure = result.getProperty("failure")
                val log = result.getProperty("log").orEmpty()
                if (failure != null || log.isNotBlank()) {
                    details.appendLine("<details><summary>$api</summary>").appendLine()
                    failure?.let { details.appendLine("**Failure:** `${it.replace("`", "'")}`").appendLine() }
                    if (log.isNotBlank()) details.appendLine("```").appendLine(log).appendLine("```")
                    details.appendLine("</details>").appendLine()
                }
            }
            appendLine()
            append(details)
        }
        logger.lifecycle(report)
        System.getenv("GITHUB_STEP_SUMMARY")?.let { File(it).appendText(report + "\n") }
    }
}

val renderPathTests = renderPathApis.map { api ->
    tasks.register<Test>("renderPathTest_$api") {
        group = "mediamp"
        description = "Plays a known clip in a Compose window on Skiko's $api render API and checks the screen"
        val testCompilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
        testClassesDirs = testCompilation.output.classesDirs
        classpath = files(testCompilation.output.allOutputs, testCompilation.runtimeDependencyFiles)
        filter { includeTestsMatching("*RenderPathMatrixTest") }
        val outputDir = renderPathMatrixDir.get().dir(api).asFile
        systemProperty("skiko.renderApi", api)
        systemProperty("mediamp.mpv.renderpath.outputDir", outputDir.absolutePath)
        getPropertyOrNull("mediamp.mpv.renderpath.strict")?.let { systemProperty("mediamp.mpv.renderpath.strict", it) }
        // The outcome depends on the machine's GPU and drivers, which are not task inputs.
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        doFirst { outputDir.deleteRecursively() }
        finalizedBy(renderPathMatrixReport)
    }
}
renderPathMatrixReport.configure { mustRunAfter(renderPathTests) }

tasks.register("renderPathMatrix") {
    group = "mediamp"
    description = "Runs the render path matrix for this host (use --continue to run every cell)"
    dependsOn(renderPathTests)
}

// The Android AAR bundles the LGPL libmpv (.so) built with -Dgpl=false; every other
// publication of this module is pure Kotlin/JNI glue (Apache-2.0). Desktop natives ship in
// the separate mediamp-mpv-runtime-* artifacts (see MpvRuntimePublishing.kt).
setPublicationLicenses("android", PomLicenses.APACHE_WITH_LGPL_RUNTIME)

mavenPublishing {
    configure(
        KotlinMultiplatform(JavadocJar.Empty(), SourcesJar.Sources(), listOf("debug", "release")),
    )
    publishToMavenCentral()
    signAllPublicationsIfEnabled(project)
    configurePom(project)
}

val cleanNativeBuild = tasks.register("cleanNativeBuild", Delete::class.java) {
    group = "mediamp"
    delete(legacyNativeBuildDir, projectDir.resolve(".cxx"))
}

tasks.named("clean") {
    dependsOn(cleanNativeBuild)
}



idea {
    module {
        excludeDirs.add(legacyNativeBuildDir)
        excludeDirs.add(file("cmake-build-debug"))
        excludeDirs.add(file("cmake-build-release"))
    }
}
