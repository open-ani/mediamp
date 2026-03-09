/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import ffmpeg.configureMediampFfmpegModule

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
    }
}

configureMediampFfmpegModule()