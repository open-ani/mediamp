import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost

/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")

    `mpp-lib-targets`
    kotlin("plugin.serialization")
//    id("org.jetbrains.kotlinx.atomicfu")
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "MediaMP data source implementation for Kotlinx IO"

android {
    namespace = "org.openani.mediamp.source.kotlinx.io"
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
            api(projects.mediampApi)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            implementation(libs.androidx.annotation)
        }
        commonTest.dependencies {
            api(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
        }
        desktopMain.dependencies {
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