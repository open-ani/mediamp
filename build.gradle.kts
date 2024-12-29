/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import com.android.build.api.dsl.CommonExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    id(libs.plugins.kotlin.multiplatform.get().pluginId) apply false
    id(libs.plugins.kotlin.android.get().pluginId) apply false
    id(libs.plugins.kotlin.jvm.get().pluginId) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    id(libs.plugins.kotlin.plugin.compose.get().pluginId) apply false
//    id("org.jetbrains.kotlinx.atomicfu") version libs.versions.atomicfu apply false
//    alias(libs.plugins.kotlinx.atomicfu) apply false
    id(libs.plugins.compose.get().pluginId) apply false
    id(libs.plugins.android.library.get().pluginId) apply false
    id(libs.plugins.android.application.get().pluginId) apply false
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId) apply false
    idea
}

allprojects {
    group = "org.openani.mediamp"
    version = properties["version.name"].toString()

    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://androidx.dev/storage/compose-compiler/repository/")
    }
}

extensions.findByName("buildScan")?.withGroovyBuilder {
    setProperty("termsOfServiceUrl", "https://gradle.com/terms-of-service")
    setProperty("termsOfServiceAgree", "yes")
}

subprojects {
    afterEvaluate {
        val jdkVersion = project.findProperty("jvm.toolchain.version")?.toString() ?: "1.8"

        val javaVersion = JavaVersion.toVersion(jdkVersion)
        extensions.findByType<KotlinMultiplatformExtension>()?.apply {
            compilerOptions {
                jvmToolchain {
                    languageVersion = JavaLanguageVersion.of(jdkVersion)
                }
            }
        }
        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget = JvmTarget.fromTarget(jdkVersion.toString())
            }
        }
        extensions.findByType(JavaPluginExtension::class.java)?.run {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }
        extensions.findByType(CommonExtension::class)?.apply {
            compileOptions {
                sourceCompatibility = javaVersion
                targetCompatibility = javaVersion
            }
        }
    }
}

idea {
    module {
        excludeDirs.add(file(".kotlin"))
    }
}
