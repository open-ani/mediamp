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
    `maven-publish`
}

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

publishing {
    publications {
        create<MavenPublication>("mediampSourceKotlinxIO") {
            from(components["kotlin"])

            pom {
                name = "Mediamp Source - Kotlinx IO"
                description = "Mediamp data source implementation for Kotlinx IO"
                url = "https://github.com/open-ani/mediamp"

                licenses {
                    name = ""
                    url = ""
                }

                developers {
                    developer {
                        id = "open-ani"
                        name = "The OpenAni Team and contributors"
                        email = ""
                    }
                }

                scm {
                    connection = "scm:git:https://github.com/open-ani/mediamp.git"
                    developerConnection = "scm:git:git@github.com:open-ani/mediamp.git"
                    url = "https://github.com/open-ani/mediamp"
                }
            }
        }
    }

    repositories {
        maven {
            name = "BuildDirectory"
            url = uri(layout.buildDirectory.dir("testPublicationRepo"))
        }
    }
}