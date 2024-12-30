/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
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
    implementation(libs.vlcj)
    implementation(libs.jna)
    implementation(libs.jna.platform)
}

kotlin {
    jvmToolchain(8)
}

mavenPublishing {
    configure(JavaLibrary(JavadocJar.Empty(), true))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    configurePom(project)
}