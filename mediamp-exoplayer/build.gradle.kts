/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import com.vanniktech.maven.publish.AndroidMultiVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("android")
    id("com.android.library")

    `mpp-lib-targets`
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "MediaMP backend using ExoPlayer"

android {
    namespace = "org.openani.mediamp.exoplayer"
    compileSdk = property("android.compile.sdk").toString().toInt()
    defaultConfig {
        minSdk = getIntProperty("android.min.sdk")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    jvmToolchain(8)
}

dependencies {
    api(projects.mediampApi)
    implementation(libs.androidx.media3.exoplayer)
}

mavenPublishing {
    configure(
        AndroidMultiVariantLibrary(
            sourcesJar = true,
            publishJavadocJar = true,
            includedBuildTypeValues = setOf("debug", "release"),
        ),
    )
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublicationsIfEnabled(project)
    configurePom(project)
}