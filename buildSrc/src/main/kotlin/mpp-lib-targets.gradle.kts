/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalComposeLibrary::class)

import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.ComposePlugin
import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.HasConfigurableKotlinCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/*
 * 配置 JVM + Android 的 compose 项目. 默认不会配置 resources. 
 * 
 * 该插件必须在 kotlin, compose, android 之后引入.
 * 
 * 如果开了 android, 就会配置 desktop + android, 否则只配置 jvm.
 */

val android = extensions.findByType(LibraryExtension::class)
val composeExtension = extensions.findByType(ComposeExtension::class)

val kotlinMultiplatformExtension = extensions.findByType<KotlinMultiplatformExtension>()
kotlinMultiplatformExtension?.apply {
    /**
     * 平台架构:
     * ```
     * common
     *   - jvm (可访问 JDK, 但不能使用 Android SDK 没有的 API)
     *     - android (可访问 Android SDK)
     *     - desktop (可访问 JDK)
     *   - native
     *     - apple
     *       - ios
     *         - iosArm64
     *         - iosSimulatorArm64 TODO
     * ```
     *
     * `native - apple - ios` 的架构是为了契合 Kotlin 官方推荐的默认架构. 以后如果万一要添加其他平台, 可方便添加.
     */
    if (project.enableIos) {
        iosArm64()
        iosX64()
        iosSimulatorArm64() // to run tests
        // no x86
    }
    if (android != null) {
        jvm("desktop")
        androidTarget {
            instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.instrumentedTest)
        }

        applyDefaultHierarchyTemplate {
            common {
                group("jvm") {
                    withJvm()
                    withAndroidTarget()
                }
                group("skiko") {
                    withJvm()
                    withNative()
                }
            }
        }

    } else {
        jvm()

        applyDefaultHierarchyTemplate()
    }

    sourceSets.commonMain.dependencies {
        // 添加常用依赖
        if (composeExtension != null) {
            val compose = ComposePlugin.Dependencies(project)
            // Compose
            api(compose.foundation)
            api(compose.animation)
            api(compose.ui)
            api(compose.material3)
            api(compose.materialIconsExtended)
            api(compose.runtime)
        }
    }
    sourceSets.commonTest.dependencies {
        // https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-test.html#writing-and-running-tests-with-compose-multiplatform
        if (composeExtension != null) {
            val compose = ComposePlugin.Dependencies(project)
            implementation(compose.uiTest)
        }
    }

    if (composeExtension != null) {
        sourceSets.getByName("desktopMain").dependencies {
            val compose = ComposePlugin.Dependencies(project)
            implementation(compose.desktop.uiTestJUnit4)
        }
    }

    if (project.findProperty("mediamp.enable.ios")?.toString()?.toBoolean() != false) {
        // ios testing workaround
        // https://developer.squareup.com/blog/kotlin-multiplatform-shared-test-resources/
        tasks.register<Copy>("copyiOSTestResources") {
            from("src/commonTest/resources")
            into("build/bin/iosSimulatorArm64/debugTest/resources")
        }
        tasks.named("iosSimulatorArm64Test") {
            dependsOn("copyiOSTestResources")
        }
    }
}

(extensions.findByType<KotlinBaseExtension>() as? HasConfigurableKotlinCompilerOptions<*>)?.apply {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

configure<KotlinBaseExtension> {
    val androidTestExtVersion = versionCatalogs.named("libs").findVersion("androidx-test-ext-junit").get()

    when {
        kotlinMultiplatformExtension != null -> { // is kotlin multiplatform
            if (android != null) {
                dependencies {
                    "androidInstrumentedTestImplementation"(kotlin("test"))
                }
            }

            if (android != null && composeExtension != null) {
                val composeVersion = versionCatalogs.named("libs").findVersion("jetpack-compose").get()
                listOf(
                    sourceSets.getByName("androidInstrumentedTest"),
                    sourceSets.getByName("androidUnitTest"),
                ).forEach { sourceSet ->
                    sourceSet.dependencies {
                        // https://developer.android.com/develop/ui/compose/testing#setup
                        implementation("androidx.compose.ui:ui-test-junit4-android:${composeVersion}")
                        implementation("androidx.compose.ui:ui-test-manifest:${composeVersion}")
                    }
                }

                dependencies {
                    "debugImplementation"("androidx.compose.ui:ui-test-manifest:${composeVersion}")
                }

                dependencies {
                    "androidInstrumentedTestImplementation"("androidx.test.ext:junit:${androidTestExtVersion}")
                    "androidInstrumentedTestImplementation"("androidx.test.ext:junit-ktx:${androidTestExtVersion}")
                }
            }
        }

        android != null -> { // is android single platform
            dependencies {
                "androidTestImplementation"(kotlin("test"))
                "androidTestImplementation"("androidx.test.ext:junit:${androidTestExtVersion}")
                "androidTestImplementation"("androidx.test.ext:junit-ktx:${androidTestExtVersion}")
            }
        }
    }
}

if (android != null) {
    kotlinMultiplatformExtension?.apply {
        sourceSets {
            // Workaround for MPP compose bug, don't change
            removeIf { it.name == "androidAndroidTestRelease" }
            removeIf { it.name == "androidTestFixtures" }
            removeIf { it.name == "androidTestFixturesDebug" }
            removeIf { it.name == "androidTestFixturesRelease" }
        }
    }
    if (composeExtension != null) {
        tasks.named("generateComposeResClass") {
            dependsOn("generateResourceAccessorsForAndroidUnitTest")
        }
        tasks.withType(KotlinCompilationTask::class) {
            dependsOn(tasks.matching { it.name == "generateComposeResClass" })
            dependsOn(tasks.matching { it.name == "generateResourceAccessorsForAndroidRelease" })
            dependsOn(tasks.matching { it.name == "generateResourceAccessorsForAndroidUnitTest" })
            dependsOn(tasks.matching { it.name == "generateResourceAccessorsForAndroidUnitTestRelease" })
            dependsOn(tasks.matching { it.name == "generateResourceAccessorsForAndroidUnitTestDebug" })
            dependsOn(tasks.matching { it.name == "generateResourceAccessorsForAndroidDebug" })
        }
    }

    android.apply {
        compileSdk = getIntProperty("android.compile.sdk")
        defaultConfig {
            minSdk = getIntProperty("android.min.sdk")
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        buildTypes.getByName("release") {
            isMinifyEnabled = false // shared 不能 minify, 否则构建 app 会失败
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                *sharedAndroidProguardRules(),
            )
        }
        buildFeatures {
            if (composeExtension != null) {
                compose = true
            }
        }
    }
}
