/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("multiplatform")
    id("com.android.library")

    kotlin("plugin.compose")
    id("org.jetbrains.compose")

    `mpp-lib-targets`
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
    idea
}

description = "MediaMP backend using MPV"

val archs = buildList {
    val abis = getPropertyOrNull("ani.android.abis")?.trim()
    if (!abis.isNullOrEmpty()) {
        addAll(abis.split(",").map { it.trim() })
    } else {
        add("arm64-v8a")
        add("armeabi-v7a")
        add("x86_64")
    }
}

kotlin {
    sourceSets {
//        androidMain {
//            kotlin.srcDirs(listOf("gen/java"))
//        }
        commonMain {
            dependencies {
                api(projects.mediampApi)
                implementation(projects.mediampInternalUtils)
            }
        }
        getByName("jvmMain").dependencies { 
            
        }
        desktopMain.dependencies {
            api(libs.jna.platform)
        }
    }
}

//kotlin.sourceSets.getByName("jvmMain") {
//    java.setSrcDirs(listOf("gen/java"))
//}

android {
    namespace = "org.openani.mediamp.mpv"
    defaultConfig {
        ndk {
            // Specifies the ABI configurations of your native
            // libraries Gradle should build and package with your app.
            abiFilters.clear()
            //noinspection ChromeOsAbiSupport
            abiFilters += archs
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            //noinspection ChromeOsAbiSupport
            include(*archs.toTypedArray())
            isUniversalApk = true // 额外构建一个
        }
    }
    externalNativeBuild {
        cmake {
            path = projectDir.resolve("CMakeLists.txt")
        }
    }
}

val nativeBuildDir = projectDir.resolve("build-ci")

val configureCMakeDesktop = tasks.register("configureCMakeDesktop", Exec::class.java) {
    group = "mediamp"

    val cmake = getPropertyOrNull("CMAKE") ?: "cmake"
    val ninja = getPropertyOrNull("NINJA") ?: "ninja"

    // Prefer clang, as the CI is tested with Clang
    val compilerC = getPropertyOrNull("CMAKE_C_COMPILER") ?: kotlin.run {
        when (getOs()) {
            Os.Windows -> {
                null
            }

            Os.Unknown,
            Os.MacOS,
            Os.Linux -> {
                File("/usr/bin/clang").takeIf { it.exists() }
                    ?: File("/usr/bin/gcc").takeIf { it.exists() }
            }
        }?.absolutePath?.also {
            logger.info("Using C compiler: $it")
        }
    }
    val compilerCxx = getPropertyOrNull("CMAKE_CXX_COMPILER") ?: kotlin.run {
        when (getOs()) {
            Os.Windows -> {
                File("C:/Program Files/LLVM/bin/clang++.exe").takeIf { it.exists() }
                    ?: File("C:/Program Files/LLVM/bin/clang++.exe").takeIf { it.exists() }
            }

            Os.Unknown,
            Os.MacOS,
            Os.Linux -> {
                File("/usr/bin/clang++").takeIf { it.exists() }
                    ?: File("/usr/bin/g++").takeIf { it.exists() }
            }
        }?.absolutePath?.also {
            logger.info("Using CXX compiler: $it")
        }
    }
    val isWindows = getOs() == Os.Windows

    inputs.file(projectDir.resolve("CMakeLists.txt"))
    outputs.dir(nativeBuildDir)

    fun String.sanitize(): String {
        return this.replace("\\", "/").trim()
    }

    val buildType = getPropertyOrNull("CMAKE_BUILD_TYPE") ?: "Debug"
    check(buildType == "Debug" || buildType == "Release" || buildType == "RelWithDebInfo" || buildType == "MinSizeRel") {
        "Invalid build type: '$buildType'. Supported: Debug, Release, RelWithDebInfo, MinSizeRel"
    }

    commandLine = buildList {
        add(cmake)
        add("-DCMAKE_BUILD_TYPE=$buildType")
        add("-DCMAKE_C_FLAGS_RELEASE=-O3")
        if (!isWindows) {
            compilerC?.let { add("-DCMAKE_C_COMPILER=${compilerC.sanitize()}") }
            compilerCxx?.let { add("-DCMAKE_CXX_COMPILER=${compilerCxx.sanitize()}") }
            add("-DCMAKE_MAKE_PROGRAM=${ninja.sanitize()}")
            add("-G")
            add("Ninja")
        } else {
            getPropertyOrNull("CMAKE_TOOLCHAIN_FILE")?.let { add("-DCMAKE_TOOLCHAIN_FILE=${it.sanitize()}") }
            if (getPropertyOrNull("USE_NINJA")?.toBooleanStrict() == true) {
                add("-DCMAKE_MAKE_PROGRAM=${ninja.sanitize()}")
                add("-G")
                add("Ninja")
            }
        }
        add("-S")
        add(projectDir.absolutePath)
        add("-B")
        add(nativeBuildDir.absolutePath)
    }
    logger.warn(commandLine.joinToString(" "))
}

val buildCMakeDesktop = tasks.register("buildCMakeDesktop", Exec::class.java) {
    group = "mediamp"
    dependsOn(configureCMakeDesktop)

    val cmake = getPropertyOrNull("CMAKE") ?: "cmake"
    val isWindows = getOs() == Os.Windows
    val buildType = getPropertyOrNull("CMAKE_BUILD_TYPE") ?: "Debug"

    inputs.file(projectDir.resolve("CMakeLists.txt"))
    inputs.dir(projectDir.resolve("src/cpp/include"))
    inputs.dir(projectDir.resolve("src/cpp"))
    outputs.dir(nativeBuildDir)

    commandLine = listOf(
        cmake,
        "--build", nativeBuildDir.absolutePath,
        "--target", "mediampv",
        *if (isWindows && buildType == "Release") arrayOf("--config", "Release") else emptyArray(),
        "-j", Runtime.getRuntime().availableProcessors().toString(),
    )
    logger.warn(commandLine.joinToString(" "))
}

tasks.withType(KotlinJvmCompile::class) {
    dependsOn(buildCMakeDesktop)
}


val supportedOsTriples = listOf("macos-aarch64", "macos-x64", "windows-x64")

val nativeJarForCurrentPlatform = tasks.register("nativeJarForCurrentPlatform", Jar::class.java) {
    dependsOn(buildCMakeDesktop)

    group = "mediamp"
    description = "Create a jar for the native files for current platform"

    val buildType = getPropertyOrNull("CMAKE_BUILD_TYPE") ?: "Debug"
    archiveClassifier.set(getOsTriple())

    when (getOs()) {
        Os.MacOS -> {
            // build-ci/libmediampv.dylib
            // build-ci/deps/*.dylib
            from(buildCMakeDesktop.map { it.outputs.files.singleFile.resolve("libmediampv.dylib") })
            from(buildCMakeDesktop.map { it.outputs.files.singleFile.resolve("deps").listFiles().orEmpty() })
        }

        Os.Linux -> {
            // build-ci/libmediampv.so
            // build-ci/deps/*.so
            from(buildCMakeDesktop.map { it.outputs.files.singleFile.resolve("libmediampv.so") })
            from(buildCMakeDesktop.map { it.outputs.files.singleFile.resolve("deps").listFiles().orEmpty() })
        }

        Os.Windows -> {
            // build-ci/Debug/mediampv.dll
            // build-ci/Debug/*.dll
            from(buildCMakeDesktop.map { it.outputs.files.singleFile.resolve(buildType).listFiles().orEmpty() })
        }

        else -> {}
    }

}

val nativeJarsDir = layout.buildDirectory.dir("native-jars")
val copyNativeJarForCurrentPlatform = tasks.register("copyNativeJarForCurrentPlatform", Copy::class.java) {
    dependsOn(nativeJarForCurrentPlatform)
    description = "Copy native jar for current platform"
    group = "mediamp"
    from(nativeJarForCurrentPlatform.flatMap { it.archiveFile })
    into(nativeJarsDir)
}

tasks.named("assemble") {
    dependsOn(copyNativeJarForCurrentPlatform)
}

mavenPublishing {
    configure(KotlinMultiplatform(JavadocJar.Empty(), true, androidVariantsToPublish = listOf("release", "debug")))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublicationsIfEnabled(project)
    configurePom(project)
}

tasks
    .matching { it.name.startsWith("publishDesktopPublicationTo") }
    .all { dependsOn(copyNativeJarForCurrentPlatform) }

if (getPropertyOrNull("mediamp.sign.publications.disabled")?.toBoolean() != true) {
    tasks.getByName("signDesktopPublication") {
        dependsOn(copyNativeJarForCurrentPlatform)
    }
}

afterEvaluate {
    publishing {
        publications {
            getByName("desktop", MavenPublication::class) {
                val platforms = if (getLocalProperty("ani.publishing.onlyHostOS") == "true") {
                    listOf(getOsTriple())
                } else {
                    supportedOsTriples
                }
                platforms.forEach { platform ->
                    artifact(nativeJarsDir.map { it.file("${project.name}-${project.version}-$platform.jar") }) {
                        classifier = platform
                    }
                }
            }
        }
    }
}

val cleanNativeBuild = tasks.register("cleanNativeBuild", Delete::class.java) {
    group = "mediamp"
    // desktop and android build
    delete(nativeBuildDir, projectDir.resolve(".cxx"))
}

tasks.named("clean") {
    dependsOn(cleanNativeBuild)
}



idea {
    module {
        excludeDirs.add(nativeBuildDir)
        excludeDirs.add(file("cmake-build-debug"))
        excludeDirs.add(file("cmake-build-release"))
    }
}