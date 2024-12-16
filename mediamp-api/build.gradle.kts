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
        implementation(libs.kotlinx.io.core)
        implementation(libs.kotlinx.coroutines.core)
    }
    sourceSets.commonTest.dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
    sourceSets.androidMain.dependencies {
        implementation(libs.androidx.compose.ui.tooling.preview)
        implementation(libs.androidx.compose.ui.tooling)
    }
    sourceSets.desktopMain.dependencies {
    }
    sourceSets.iosMain.dependencies {
    }
}

android {
    namespace = "org.openani.mediamp.api"
}
