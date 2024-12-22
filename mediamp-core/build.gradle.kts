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
    alias(libs.plugins.vanniktech.mavenPublish)
}

android {
    namespace = "org.openani.mediamp.core"
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.mediampApi)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.annotation)
        }
        commonTest.dependencies {
            api(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.compose.ui.tooling.preview)
            implementation(libs.androidx.compose.ui.tooling)
            implementation(libs.compose.material3.adaptive.core.get().toString()) {
                exclude("androidx.window.core", "window-core")
            }
            implementation(libs.androidx.media3.ui)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.exoplayer.dash)
            implementation(libs.androidx.media3.exoplayer.hls)
        }
        desktopMain.dependencies {
            api(compose.desktop.currentOs) {
                exclude(compose.material) // We use material3
            }

            api(libs.kotlinx.coroutines.swing)
            implementation(libs.vlcj)
            implementation(libs.jna)
            implementation(libs.jna.platform)
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
                name = "The OpenAni Team and contributors"
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