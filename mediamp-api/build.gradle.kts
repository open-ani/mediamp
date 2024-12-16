plugins {
    kotlin("multiplatform")
    id("com.android.library")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")

    `mpp-lib-targets`
    kotlin("plugin.serialization")
}

kotlin {
    explicitApi()
    sourceSets.commonMain.dependencies {
        implementation(libs.kotlinx.io.core) // TODO: 2024/12/16 remove 
        implementation(libs.kotlinx.coroutines.core)
    }
    sourceSets.commonTest.dependencies {
        api(kotlin("test"))
        api(libs.kotlinx.coroutines.test)
    }
    sourceSets.androidMain.dependencies {
        implementation(libs.androidx.compose.ui.tooling.preview)
        implementation(libs.androidx.compose.ui.tooling)
    }
    sourceSets.getByName("jvmTest").dependencies {
        api(libs.junit.jupiter.api)
        runtimeOnly(libs.junit.jupiter.engine)
    }
    sourceSets.desktopMain.dependencies {
    }
    sourceSets.iosMain.dependencies {
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

android {
    namespace = "org.openani.mediamp.api"
}
