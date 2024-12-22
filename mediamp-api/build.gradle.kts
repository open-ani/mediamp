import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
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
    alias(libs.plugins.vanniktech.mavenPublish)
}



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
        androidMain.dependencies {
            implementation(libs.androidx.compose.ui.tooling.preview)
            implementation(libs.androidx.compose.ui.tooling)
        }
        getByName("jvmTest").dependencies {
            api(libs.junit.jupiter.api)
            runtimeOnly(libs.junit.jupiter.engine)
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

tasks.withType<Test> {
    useJUnitPlatform()
}

mavenPublishing {
    configure(KotlinMultiplatform(JavadocJar.Empty(), true, listOf("release")))

    publishToMavenCentral(SonatypeHost.DEFAULT)

    signAllPublications()

    pom {
        name = "MediaMP API"
        description = "Core API for MediaMP"
        url = "https://github.com/open-ani/mediamp"

        licenses {
            name = "GNU General Public License, version 3"
            url = "https://github.com/open-ani/mediamp/blob/main/LICENSE"
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