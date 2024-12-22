/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.kotlin
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetsContainer
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmAndroidCompilation
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.io.File

fun Project.sharedAndroidProguardRules(): Array<File> {
    val dir = rootProject.projectDir
    return listOf(
        dir.resolve("proguard-rules.pro"),
        dir.resolve("proguard-rules-keep-names.pro"),
    ).filter {
        it.exists()
    }.toTypedArray()
}

private fun Project.versionCatalogLibs(): VersionCatalog =
    project.extensions.getByType<VersionCatalogsExtension>().named("libs")

private operator fun VersionCatalog.get(name: String): String = findVersion(name).get().displayName

private fun Project.kotlinCommonCompilerOptions(): KotlinCommonCompilerOptions = when (val ext = kotlinExtension) {
    is KotlinJvmProjectExtension -> ext.compilerOptions
    is KotlinAndroidProjectExtension -> ext.compilerOptions
    is KotlinMultiplatformExtension -> ext.compilerOptions
    else -> error("Unsupported kotlinExtension: ${ext::class}")
}

val Project.DEFAULT_JVM_TOOLCHAIN_VENDOR
    get() = getPropertyOrNull("jvm.toolchain.vendor")?.let { JvmVendorSpec.matching(it) }

private fun Project.getProjectPreferredJvmTargetVersion() = extra.runCatching { get("ani.jvm.target") }.fold(
    onSuccess = { JavaVersion.toVersion(it.toString()) },
    onFailure = { JavaVersion.toVersion(getPropertyOrNull("jvm.toolchain.version")?.toInt() ?: 17) },
)

fun Project.configureJvmTarget() {
    val ver = getProjectPreferredJvmTargetVersion()
    logger.info("JVM target for project ${this.path} is: $ver")

    // 我也不知道到底设置谁就够了, 反正全都设置了

    tasks.withType(KotlinJvmCompile::class.java) {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(ver.toString()))
    }

    tasks.withType(KotlinCompile::class.java) {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(ver.toString()))
    }

    tasks.withType(JavaCompile::class.java) {
        sourceCompatibility = ver.toString()
        targetCompatibility = ver.toString()
    }

    extensions.findByType(KotlinProjectExtension::class)?.apply {
        jvmToolchain {
            vendor.set(DEFAULT_JVM_TOOLCHAIN_VENDOR)
            languageVersion.set(JavaLanguageVersion.of(ver.getMajorVersion()))
        }
    }

    extensions.findByType(JavaPluginExtension::class)?.apply {
        toolchain {
            vendor.set(DEFAULT_JVM_TOOLCHAIN_VENDOR)
            languageVersion.set(JavaLanguageVersion.of(ver.getMajorVersion()))
            sourceCompatibility = ver
            targetCompatibility = ver
        }
    }

    withKotlinTargets {
        it.compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xdont-warn-on-error-suppression")
                }
            }
            if (this is KotlinJvmAndroidCompilation) {
                compileTaskProvider.configure {
                    compilerOptions {
                        jvmTarget.set(JvmTarget.fromTarget(ver.toString()))
                    }
                }
            }
        }
    }

    extensions.findByType(JavaPluginExtension::class.java)?.run {
        sourceCompatibility = ver
        targetCompatibility = ver
    }

    extensions.findByType(CommonExtension::class)?.apply {
        compileOptions {
            sourceCompatibility = ver
            targetCompatibility = ver
        }
    }
}

fun Project.withKotlinTargets(fn: (KotlinTarget) -> Unit) {
    extensions.findByType(KotlinTargetsContainer::class.java)?.let { kotlinExtension ->
        // find all compilations given sourceSet belongs to
        kotlinExtension.targets
            .all {
                fn(this)
            }
    }
}