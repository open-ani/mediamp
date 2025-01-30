/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

plugins {
    kotlin("multiplatform")
    id("com.android.library")

    `mpp-lib-targets`
    kotlin("plugin.serialization")
}

description = "Test suite for MediaMP Core API"

android {
    namespace = "org.openani.mediamp.api.test"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    jvmToolchain(8)
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.io.core) // TODO: 2024/12/16 remove 
            compileOnly(libs.androidx.annotation)
            api(libs.kotlinx.coroutines.core)
            
            implementation(projects.mediampApi)
            
            api(kotlin("test-annotations-common", libs.versions.kotlin.get()))
            api(libs.kotlinx.coroutines.test)
        }
        sourceSets["jvmMain"].dependencies {
            api(kotlin("test-junit", libs.versions.kotlin.get()))
        }
        iosMain.dependencies {
            implementation(libs.androidx.annotation)
        }
        androidMain.dependencies {
            api(libs.androidx.annotation)
        }
    }
}