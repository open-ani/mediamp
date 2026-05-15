@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

description = "Browser wasm preview app for MediaMP"

kotlin {
    wasmJs {
        outputModuleName = "mediamp-web-preview"
        browser()
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(projects.mediampApi)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.browser)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
