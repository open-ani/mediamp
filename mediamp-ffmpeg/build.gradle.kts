/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import ffmpeg.configureMediampFfmpegModule
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    `mpp-lib-targets`
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
    `maven-publish`
}

description = "FFmpeg binary wrapper for MediaMP"

kotlin {
    explicitApi()
    androidLibrary {
        namespace = "org.openani.mediamp.ffmpeg"
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        desktopMain.dependencies {
            implementation(libs.ffmpeg.platform)
        }
        androidMain.dependencies {
            // Pure Java API — native .so files are provided via jniLibs below.
            implementation(libs.ffmpeg)
        }
        androidHostTest.dependencies {
            // JavaCPP platform libs needed to load native code in host JVM tests
            implementation(libs.ffmpeg.platform)
        }
    }
}

// Custom configuration to resolve JavaCPP JNI bridge .so files per ABI.
// We use the base artifact (libs.ffmpeg) for compilation, but still need
// libjni*.so at runtime.  This configuration fetches the classifier jars
// without adding them as transitive dependencies of the published aar.
val javacppNative by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    val ffmpegVersion = libs.versions.ffmpeg.get()
    javacppNative("org.bytedeco:ffmpeg:$ffmpegVersion:android-arm64")
    javacppNative("org.bytedeco:ffmpeg:$ffmpegVersion:android-x86_64")
}

configureMediampFfmpegModule()

kotlin {
    targets.withType(KotlinNativeTarget::class.java)
        .matching { target -> target.name == "iosArm64" || target.name == "iosSimulatorArm64" }
        .configureEach {
            val capitalizedTargetName = name.replaceFirstChar { it.uppercase() }
            val frameworkTaskName = "ffmpegAppleFramework$capitalizedTargetName"
            val frameworkSearchPath = project.layout.buildDirectory.dir("apple-framework/$capitalizedTargetName")
            val frameworkSearchPathValue = frameworkSearchPath.get().asFile.absolutePath

            val ffmpegSrcDir = project.projectDir.resolve("ffmpeg")
            val stubHeadersDir = project.projectDir.resolve("src/nativeInterop/cinterop/stub-headers")

            compilations.getByName("main").cinterops.create("libavffi") {
                defFile(project.file("src/nativeInterop/cinterop/libav_ffi.def"))
                compilerOpts("-I${ffmpegSrcDir.absolutePath}", "-I${stubHeadersDir.absolutePath}")
            }
            binaries.configureEach {
                linkerOpts("-F$frameworkSearchPathValue", "-framework", "MediampFFmpegKit")
            }

            if (project.tasks.names.contains(frameworkTaskName)) {
                project.tasks.named("cinteropLibavffi$capitalizedTargetName") {
                    dependsOn(frameworkTaskName)
                }
                project.tasks.matching { task ->
                    task.name == "compileKotlin$capitalizedTargetName" ||
                        (task.name.startsWith("link") && task.name.endsWith(capitalizedTargetName))
                }.configureEach {
                    dependsOn(frameworkTaskName)
                }
            }
        }
}

// Copy Apple framework into test bundle so dynamic linker can find it at runtime.
val copyiOSFrameworkForTests = tasks.register<Copy>("copyiOSFrameworkForTests") {
    dependsOn("ffmpegAppleFrameworkIosSimulatorArm64")
    from(project.layout.buildDirectory.dir("apple-framework/IosSimulatorArm64/MediampFFmpegKit.framework"))
    into(project.layout.buildDirectory.dir("bin/iosSimulatorArm64/debugTest/Frameworks/MediampFFmpegKit.framework"))
}

tasks.named("linkDebugTestIosSimulatorArm64") {
    dependsOn(copyiOSFrameworkForTests)
}
