/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
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

    `mpp-lib-targets`
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "Shared native runtime loader for MediaMP"

kotlin {
    explicitApi()
    androidLibrary {
        namespace = "org.openani.mediamp.nativeloader"
    }
    sourceSets {
        desktopMain.dependencies {
            implementation(libs.jna)
            implementation(libs.jna.platform)
        }
    }
}

mavenPublishing {
    configure(KotlinMultiplatform(JavadocJar.Empty(), SourcesJar.Sources(), listOf("debug", "release")))
    publishToMavenCentral()
    signAllPublicationsIfEnabled(project)
    configurePom(project)
}
