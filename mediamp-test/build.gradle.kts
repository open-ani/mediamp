import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")

    `mpp-lib-targets`
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "Test backend for MediaMP"


kotlin {
    explicitApi()
    androidLibrary {
        namespace = "org.openani.mediamp.test"
        /*publishLibraryVariants("release")*/
        /*compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }*/
    }
    jvmToolchain(11)
    sourceSets {
        commonMain.dependencies {
            compileOnly(libs.androidx.annotation)
            api(libs.kotlinx.coroutines.core)
            implementation(projects.mediampApi)
        }
        
        commonTest.dependencies {
            implementation(kotlin("test-annotations-common", libs.versions.kotlin.get()))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit", libs.versions.kotlin.get()))
        }
        iosTest.dependencies {
            implementation(libs.androidx.annotation)
        }
        androidUnitTest.dependencies {
            implementation(libs.androidx.annotation)
        }
    }
}

mavenPublishing {
    configure(KotlinMultiplatform(JavadocJar.Empty(), SourcesJar.Sources(), listOf("debug", "release")))
    publishToMavenCentral()
    signAllPublicationsIfEnabled(project)
    configurePom(project)
}