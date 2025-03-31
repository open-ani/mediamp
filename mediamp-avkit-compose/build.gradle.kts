/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")

    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "MediaMP backend using Apple AVKit"

dependencies {
    commonMainApi(projects.mediampAvkit)
    commonMainApi(projects.mediampCompose)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    explicitApi()
}

mavenPublishing {
    configure(KotlinMultiplatform(JavadocJar.Empty(), true))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublicationsIfEnabled(project)
    configurePom(project)
}