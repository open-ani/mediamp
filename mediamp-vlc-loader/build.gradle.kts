/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm")

    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "MediaMP VLC Loader"

dependencies {
    api(libs.vlcj)
    implementation(projects.mediampInternalUtils)
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