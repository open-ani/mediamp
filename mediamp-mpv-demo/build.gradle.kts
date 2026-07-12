/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

description = "Prototype: mpv hardware decoding rendered into Compose Desktop via IOSurface/Metal (macOS only)"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // Baseline for benchmarking: the current production VLC stack.
    implementation(projects.mediampVlc)
    implementation(projects.mediampApi)
    // Production mpv path (runD3D11 smoke demo).
    implementation(projects.mediampMpv)
}

val nativeOutput = layout.buildDirectory.file("native/libmpvskiabridge.dylib")

val compileNativeBridge = tasks.register<Exec>("compileNativeBridge") {
    group = "mediamp"
    description = "Compile the mpv/IOSurface/Metal JNI bridge with clang++"
    val source = layout.projectDirectory.file("src/native/mpv_skia_bridge.mm")
    inputs.file(source)
    outputs.file(nativeOutput)
    commandLine(
        "/bin/zsh",
        layout.projectDirectory.file("build-native.sh").asFile.absolutePath,
        source.asFile.absolutePath,
        nativeOutput.get().asFile.absolutePath,
    )
    onlyIf { System.getProperty("os.name").contains("Mac") }
}

compose.desktop {
    application {
        mainClass = "org.openani.mediamp.mpvdemo.MainKt"
        jvmArgs += "-Dmpvdemo.native.lib=${nativeOutput.get().asFile.absolutePath}"
        // -Phwdec=no forces software decoding, for decode-cost comparisons.
        jvmArgs += "-Dmpvdemo.hwdec=${findProperty("hwdec") ?: "videotoolbox"}"
    }
}

// The compose desktop plugin registers "run" after evaluation; match lazily.
tasks.matching { it.name == "run" }.configureEach {
    dependsOn(compileNativeBridge)
}

// Production mediamp-mpv path (Windows D3D11 / macOS Metal / Linux GLX) against a locally
// assembled runtime: ./gradlew :mediamp-mpv-demo:runD3D11 [-Pvideo=/path/to.mp4]
tasks.register<JavaExec>("runD3D11") {
    group = "mediamp"
    description = "Run the production mediamp-mpv demo (D3D11 on Windows, Metal on macOS, GLX on Linux)"
    mainClass = "org.openani.mediamp.mpvdemo.MpvD3D11MainKt"
    classpath = sourceSets["main"].runtimeClasspath
    val runtimeTarget = when {
        System.getProperty("os.name").contains("Windows") -> "WindowsX64"
        System.getProperty("os.name").contains("Linux") -> "LinuxX64"
        System.getProperty("os.arch") == "aarch64" -> "MacosArm64"
        else -> "MacosX64"
    }
    val runtimeSubdir = if (runtimeTarget == "WindowsX64") "bin" else "lib"
    systemProperty(
        "mediamp.mpv.runtime.dir",
        project(":mediamp-mpv").layout.buildDirectory
            .dir("mpv-output/$runtimeTarget/$runtimeSubdir").get().asFile.absolutePath,
    )
    (findProperty("video") as String?)?.let { args(it) }
    (findProperty("screenshotDir") as String?)?.let { systemProperty("mpvdemo.screenshot.dir", it) }
}

// VLC baseline player for benchmarks: ./gradlew :mediamp-mpv-demo:runVlc -Pvideo=/path/to.mp4
tasks.register<JavaExec>("runVlc") {
    group = "mediamp"
    description = "Run the VLC baseline demo (same window and overlay as the mpv demo)"
    mainClass = "org.openani.mediamp.mpvdemo.VlcMainKt"
    classpath = sourceSets["main"].runtimeClasspath
    (findProperty("video") as String?)?.let { args(it) }
}
