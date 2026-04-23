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
        getByName("jvmMain").dependencies {
            implementation(projects.mediampNativeLoader)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
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

            compilations.getByName("main").cinterops.create("mediampffmpegkit") {
                defFile(project.file("src/nativeInterop/cinterop/mediamp_ffmpegkit.def"))
                compilerOpts("-F$frameworkSearchPathValue")
            }
            binaries.configureEach {
                linkerOpts("-F$frameworkSearchPathValue", "-framework", "MediampFFmpegKit")
            }

            if (project.tasks.names.contains(frameworkTaskName)) {
                project.tasks.named("cinteropMediampffmpegkit$capitalizedTargetName") {
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
