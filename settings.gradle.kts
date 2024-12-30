/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

rootProject.name = "mediamp"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev") // Compose Multiplatform pre-release versions
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

include(":mediamp-api")
include(":mediamp-compose")

include(":mediamp-vlc")
include(":mediamp-vlc-compose")
include(":mediamp-exoplayer")
include(":mediamp-exoplayer-compose")
include(":mediamp-mpv")
include(":mediamp-mpv-compose")

//include(":mediamp-preview")
include(":mediamp-source-ktxio")

include(":ci-helper")
include(":catalog")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
