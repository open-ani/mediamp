/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    alias(libs.plugins.vanniktech.mavenPublish)
    idea
}

description = "MediaMP backend using MPV"

val archs = buildList {
    val abis = getPropertyOrNull("ani.android.abis")?.trim()
    if (!abis.isNullOrEmpty()) {
        addAll(abis.split(",").map { it.trim() })
    } else {
        add("arm64-v8a")
        add("armeabi-v7a")
        add("x86_64")
    }
}

kotlin {
    jvmToolchain(8)
    jvm("desktop")
    androidTarget()

    applyDefaultHierarchyTemplate {
        common {
            group("jvm") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    sourceSets {
//        androidMain {
//            kotlin.srcDirs(listOf("gen/java"))
//        }
        getByName("jvmMain") {
            dependencies {
                
            }
        }
    }
}

//kotlin.sourceSets.getByName("jvmMain") {
//    java.setSrcDirs(listOf("gen/java"))
//}

android {
    namespace = "org.openani.mediamp.backend.mpv"
    compileSdk = property("android.compile.sdk").toString().toInt()
    defaultConfig {
        minSdk = getIntProperty("android.min.sdk")
        ndk {
            // Specifies the ABI configurations of your native
            // libraries Gradle should build and package with your app.
            abiFilters.clear()
            //noinspection ChromeOsAbiSupport
            abiFilters += archs
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            //noinspection ChromeOsAbiSupport
            include(*archs.toTypedArray())
            isUniversalApk = true // 额外构建一个
        }
    }
    externalNativeBuild {
        cmake {
            path = projectDir.resolve("CMakeLists.txt")
        }
    }
}

val nativeBuildDir = projectDir.resolve("build-native")

// TODO: configure manual build

idea {
    module {
        excludeDirs.add(nativeBuildDir)
        excludeDirs.add(file("cmake-build-debug"))
        excludeDirs.add(file("cmake-build-release"))
    }
}