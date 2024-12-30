/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    `version-catalog`
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "Mediamp Version Catalogs"

catalog {
    versionCatalog {
        version("mediamp", project.version.toString())

        val group = project.group.toString()
        library("mediamp-backend-exoplayer", group, "mediamp-backend-exoplayer").versionRef("mediamp")
        library("mediamp-backend-mpv", group, "mediamp-backend-mpv").versionRef("mediamp")
        library("mediamp-backend-vlc", group, "mediamp-backend-vlc").versionRef("mediamp")

        library("mediamp-api", group, "mediamp-api").versionRef("mediamp")
        library("mediamp-compose", group, "mediamp-compose").versionRef("mediamp")

        library("mediamp-source-kotlinx-io", group, "mediamp-source-kotlinx-io").versionRef("mediamp")
    }
}

mavenPublishing {
    configure(com.vanniktech.maven.publish.VersionCatalog())
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    configurePom(project)
}
