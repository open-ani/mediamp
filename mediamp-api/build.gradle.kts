/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")

    `mpp-lib-targets`
    kotlin("plugin.serialization")
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "Core API for MediaMP"

android {
    namespace = "org.openani.mediamp.api"
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    explicitApi()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.io.core) // TODO: 2024/12/16 remove 
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            api(kotlin("test"))
            api(libs.kotlinx.coroutines.test)
        }
        getByName("jvmTest").dependencies {
            api(libs.junit)
        }
        desktopMain.dependencies {
        }
        iosMain.dependencies {
        }
    }
    androidTarget {
        publishLibraryVariants("release")
    }
}

mavenPublishing {
    configure(KotlinMultiplatform(JavadocJar.Empty(), true, listOf("debug", "release")))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    configurePom(project)
}