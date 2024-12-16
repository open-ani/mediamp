plugins {
    kotlin("multiplatform")
    id("com.android.library")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")

    `mpp-lib-targets`
    kotlin("plugin.serialization")
}

kotlin {
    sourceSets.commonMain.dependencies {
    }
    sourceSets.commonTest.dependencies {
        api(libs.kotlinx.coroutines.core)
        api(libs.kotlinx.coroutines.test)
    }
    sourceSets.androidMain.dependencies {
        implementation(libs.androidx.compose.ui.tooling.preview)
        implementation(libs.androidx.compose.ui.tooling)
    }
    sourceSets.desktopMain.dependencies {
    }
}

android {
    namespace = "org.openani.mediamp.api"
}
