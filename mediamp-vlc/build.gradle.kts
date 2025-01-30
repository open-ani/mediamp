/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")

    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "MediaMP backend using VLC"

dependencies {
    api(projects.mediampApi)
    api(projects.mediampCompose)
    api(libs.vlcj)
    implementation(libs.jna)
    implementation(libs.jna.platform)
}

kotlin {
    explicitApi()
    jvmToolchain(8)
}

mavenPublishing {
    configure(KotlinJvm(JavadocJar.Empty(), true))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublicationsIfEnabled(project)
    configurePom(project)
}