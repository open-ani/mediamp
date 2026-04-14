/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import androidLibrary
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.gradle.kotlin.dsl.findByType
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.ComposePlugin
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.HasConfigurableKotlinCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.plugin.cocoapods.CocoapodsExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import kotlin.apply

/*
 * 配置 JVM + Android 的 compose 项目. 默认不会配置 resources. 
 * 
 * 该插件必须在 kotlin, compose, android 之后引入.
 * 
 * 如果开了 android, 就会配置 desktop + android, 否则只配置 jvm.
 */

val enableJvmTarget = project.findProperty("mediamp.jvm.target")?.toString()?.toBooleanStrict() ?: true

val androidLibraryExtension = extensions.findByType(KotlinMultiplatformExtension::class)
    ?.extensions?.findByType(KotlinMultiplatformAndroidLibraryExtension::class)
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
        iosSimulatorArm64() // to run tests
        // no x86
    }
    if (androidLibraryExtension != null) {
        if (enableJvmTarget) {
            jvm("desktop") {
                configureJvmOptions()
            }
        }
        androidLibrary {
            compileSdk = getIntProperty("android.compile.sdk")
            minSdk = getIntProperty("android.min.sdk")
            androidResources.enable = true

            withHostTestBuilder {
                sourceSetTreeName = KotlinSourceSetTree.test.name
            }

            withDeviceTestBuilder {
                sourceSetTreeName = KotlinSourceSetTree.test.name
            }.configure {
                targetSdk {
                    release(getIntProperty("android.min.sdk"))
                }
                instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                instrumentationRunnerArguments["runnerBuilder"] = "de.mannodermaus.junit5.AndroidJUnit5Builder"
                instrumentationRunnerArguments["package"] = "me.him188"
                execution = "HOST"
            }

            packaging {
                resources {
                    pickFirsts.add("META-INF/LICENSE.md")
                    pickFirsts.add("META-INF/LICENSE-notice.md")
                }
            }
            
            compilerOptions {
                jvmTarget = JvmTarget.JVM_11
            }
        }

        applyDefaultHierarchyTemplate {
            common {
                group("jvm") {
                    withJvm()
                    group("android")
                }
                group("skiko") {
                    withJvm()
                    withNative()
                }

                group("android") {
                    withCompilations { it.platformType == KotlinPlatformType.androidJvm }
                }
            }
        }

    } else {
        if (enableJvmTarget) {
            jvm {
                configureJvmOptions()
            }
        }

        applyDefaultHierarchyTemplate()
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    val libs = versionCatalogs.named("libs")
    sourceSets.commonMain.dependencies {
        // 添加常用依赖
        if (composeExtension != null) {
            // Compose
            api(libs.getLibrary("compose-foundation"))
            api(libs.getLibrary("compose-runtime"))
            api(libs.getLibrary("compose-ui"))
            api(libs.getLibrary("compose-animation"))
            api(libs.getLibrary("compose-material3"))
            api(libs.getLibrary("compose-material-icons-extended"))
        }
    }
    sourceSets.commonTest.dependencies {
        // https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-test.html#writing-and-running-tests-with-compose-multiplatform
        if (composeExtension != null) {
            implementation(libs.getLibrary("compose-ui-test"))
        }
    }

    if (composeExtension != null && enableJvmTarget) {
        sourceSets.getByName("desktopMain").dependencies {
            implementation(libs.getLibrary("compose-ui-test-junit4"))
        }
    }

    if (project.findProperty("mediamp.ios.target")?.toString()?.toBoolean() != false) {
        // ios testing workaround
        // https://developer.squareup.com/blog/kotlin-multiplatform-shared-test-resources/
        val copyiOSTestResources = tasks.register<Copy>("copyiOSTestResources") {
            from("src/commonTest/resources")
            into("build/bin/iosSimulatorArm64/debugTest/resources")
        }
        tasks.named("iosSimulatorArm64Test") {
            dependsOn(copyiOSTestResources)
        }
    }

    if (androidLibraryExtension != null) {
        sourceSets {
            // Workaround for MPP compose bug, don't change
            removeIf { it.name == "androidAndroidTestRelease" }
            removeIf { it.name == "androidTestFixtures" }
            removeIf { it.name == "androidTestFixturesDebug" }
            removeIf { it.name == "androidTestFixturesRelease" }
        }

        if (composeExtension != null) {
            tasks.named("generateComposeResClass") {
                mustRunAfter("generateResourceAccessorsForAndroidHostTest")
            }
            tasks.withType(KotlinCompilationTask::class) {
                mustRunAfter(tasks.matching { it.name == "generateComposeResClass" })
                mustRunAfter(tasks.matching { it.name == "generateResourceAccessorsForAndroidMain" })
                mustRunAfter(tasks.matching { it.name == "generateResourceAccessorsForAndroidHostTest" })
                mustRunAfter(tasks.matching { it.name == "generateResourceAccessorsForAndroidDeviceTest" })
            }

            val composeVersion = versionCatalogs.named("libs").findVersion("jetpack-compose").get()
            listOf(
                sourceSets.getByName("androidDeviceTest"),
                sourceSets.getByName("androidHostTest"),
            ).forEach { sourceSet ->
                sourceSet.dependencies {
                    // https://developer.android.com/develop/ui/compose/testing#setup
//                implementation("androidx.compose.ui:ui-test-junit4-android:${composeVersion}")
//                implementation("androidx.compose.ui:ui-test-manifest:${composeVersion}")
                    // TODO: this may cause dependency rejection when importing the project in IntelliJ.
                }
            }

            project.dependencies {
                "androidRuntimeClasspath"(libs.getLibrary("androidx-compose-ui-test-manifest"))
            }
        }
    }
}

fun HasConfigurableKotlinCompilerOptions<KotlinJvmCompilerOptions>.configureJvmOptions() {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

(extensions.findByType<KotlinBaseExtension>() as? HasConfigurableKotlinCompilerOptions<*>)?.apply {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

extensions.findByType<KotlinJvmExtension>()?.apply {
    configureJvmOptions()
}

extensions.findByType<JavaPluginExtension>()?.apply {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}