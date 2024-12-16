/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
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
//    alias(libs.plugins.kotlin.multiplatform) apply false
//    alias(libs.plugins.kotlin.android) apply false
//    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
//    alias(libs.plugins.kotlin.plugin.compose) apply false
//    id("org.jetbrains.kotlinx.atomicfu") version libs.versions.atomicfu apply false
//    alias(libs.plugins.kotlinx.atomicfu) apply false
//    alias(libs.plugins.compose) apply false
//    alias(libs.plugins.android.library) apply false
//    alias(libs.plugins.android.application) apply false
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
