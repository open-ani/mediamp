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

    `mpp-lib-targets`
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "MediaMP all-in-one bundle for Kotlin Multiplatform targeting Android, iOS, and Desktop."


kotlin {
    explicitApi()
    androidLibrary {
        namespace = "org.openani.mediamp.all"
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(projects.mediampApi)
        }
        desktopMain.dependencies {
            api(projects.mediampVlc)
            api(projects.mediampVlcLoader)
        }
        iosMain.dependencies {
            api(projects.mediampAvkit)
        }
        androidMain.dependencies {
            api(projects.mediampExoplayer)
            api(libs.androidx.media3.exoplayer.hls)
        }
    }
}

mavenPublishing {
    configure(KotlinMultiplatform(JavadocJar.Empty(), SourcesJar.Sources(), listOf("debug", "release")))
    publishToMavenCentral()
    signAllPublicationsIfEnabled(project)
    configurePom(project)
}