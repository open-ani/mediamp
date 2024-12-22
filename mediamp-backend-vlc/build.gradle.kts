/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
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

    alias(libs.plugins.vanniktech.mavenPublish)
}

dependencies {
    api(projects.mediampApi)
    api(projects.mediampCompose)
    implementation(libs.vlcj)
    implementation(libs.jna)
    implementation(libs.jna.platform)
}

mavenPublishing {
    configure(JavaLibrary(JavadocJar.Empty(), true))

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