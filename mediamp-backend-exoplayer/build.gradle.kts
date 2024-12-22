/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import com.vanniktech.maven.publish.AndroidMultiVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("android")
    id("com.android.library")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")

    alias(libs.plugins.vanniktech.mavenPublish)
}

description = "MediaMP backend using ExoPlayer"

android {
    namespace = "org.openani.mediamp.backend.exoplayer"
    compileSdk = property("android.compile.sdk").toString().toInt()
    defaultConfig {
        minSdk = getIntProperty("android.min.sdk")
    }
}

dependencies {
    api(projects.mediampApi)
    api(projects.mediampCompose)
    implementation(libs.androidx.annotation)

    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.exoplayer)

    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.hls)
}

mavenPublishing {
    configure(AndroidMultiVariantLibrary(true, true, setOf("debug", "release")))

    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    signAllPublications()

    pom {
        name = "MediaMP Core"
        description = "Core library for MediaMP"
        url = "https://github.com/open-ani/mediamp"

        licenses {
            license {
                name = "GNU General Public License, Version 3"
                url = "https://github.com/open-ani/mediamp/blob/main/LICENSE"
                distribution = "https://www.gnu.org/licenses/gpl-3.0.txt"
            }
        }

        developers {
            developer {
                id = "openani"
                name = "OpenAni and contributors"
                email = "support@openani.org"
            }
        }

        scm {
            connection = "scm:git:https://github.com/open-ani/mediamp.git"
            developerConnection = "scm:git:git@github.com:open-ani/mediamp.git"
            url = "https://github.com/open-ani/mediamp"
        }
    }
}