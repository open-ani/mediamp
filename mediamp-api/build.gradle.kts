@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")

    `mpp-lib-targets`
    kotlin("plugin.serialization")
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "Core API for MediaMP"


kotlin {
    explicitApi()
    wasmJs {
        browser()
    }
    androidLibrary {
        namespace = "org.openani.mediamp.api"
        /*publishLibraryVariants("release")*/
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.io.core) // TODO: 2024/12/16 remove 
            api(libs.androidx.annotation)
            api(libs.kotlinx.coroutines.core)
            api(compose.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("jvmTest").dependencies {
            implementation(libs.junit)
        }
        desktopMain.dependencies {
        }
        iosMain.dependencies {
            implementation(projects.mediampInternalUtils)
        }
        androidMain.dependencies {
        }
        wasmJsMain.dependencies {
            api(libs.kotlinx.browser)
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

mavenPublishing {
    configure(KotlinMultiplatform(JavadocJar.Empty(), SourcesJar.Sources(), listOf("debug", "release")))
    publishToMavenCentral()
    signAllPublicationsIfEnabled(project)
    configurePom(project)
}
