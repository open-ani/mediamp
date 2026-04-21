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

        }
        desktopMain.dependencies {
            api(libs.jna.platform)
        }
    }
}

configureMediampMpvModule()
val hostMpvTargetName = when (getOs()) {
    Os.Windows -> "WindowsX64"
    Os.Linux -> "LinuxX64"
    Os.MacOS -> if (getArch() == Arch.AARCH64) "MacosArm64" else "MacosX64"
    else -> null
}
val hostMpvOutputDir = hostMpvTargetName?.let { layout.buildDirectory.dir("mpv-output/$it") }
val hostMpvAssembleTaskName = hostMpvTargetName?.let { "mpvAssemble$it" }
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
