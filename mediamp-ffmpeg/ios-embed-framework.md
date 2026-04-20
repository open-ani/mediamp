# Embedding Published Apple XCFramework In A Consumer KMP Project

This document describes what a consumer project must do to use the published Apple runtime artifact:

```text
org.openani.mediamp:mediamp-ffmpeg-runtime-ios-xcframework:<version>@zip
```

The published artifact is a Maven `zip` that contains:

- `MediampFFmpegKit.xcframework`
- `ios-arm64`
- `ios-arm64-simulator`

There is no consumer Gradle plugin yet, so the consumer project must handle the Apple wiring itself.

## What The Consumer Must Do

A KMP consumer needs to handle four things on its own:

1. Resolve the published Apple runtime zip from Maven.
2. Unpack `MediampFFmpegKit.xcframework` to a local directory.
3. Point each Kotlin/Native iOS target at the correct slice with `cinterop` and linker flags.
4. Embed the same `xcframework` into the final Xcode app target with `Embed & Sign`.

If any of these steps is missing, the setup is incomplete:

- without unzip: Kotlin/Native has nothing to link against
- without `cinterop`: iOS source cannot see `ffmpegkit_*` symbols
- without Xcode embed: the app will fail at runtime

## Current Constraints

- Supported Apple slices are `ios-arm64` and `ios-arm64-simulator`.
- `iosX64` is not published.
- The consumer should use the same extracted `xcframework` for both Kotlin/Native linking and Xcode embedding.

## Minimal Consumer Layout

One practical layout is:

```text
your-project/
  shared/
    build.gradle.kts
    src/nativeInterop/cinterop/mediamp_ffmpegkit.def
  iosApp/
    YourApp.xcodeproj
    Frameworks/
```

Recommended flow:

- unzip into `shared/build/mediamp-ffmpeg/apple-runtime/`
- sync the same `MediampFFmpegKit.xcframework` into `iosApp/Frameworks/`
- reference `iosApp/Frameworks/MediampFFmpegKit.xcframework` from Xcode

Using a stable app-local path avoids Xcode references pointing at ephemeral `build/` internals.

## Gradle Reference

The snippet below shows one way to wire a consumer KMP module.

```kotlin
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform")
}

val mediampFfmpegVersion = "REPLACE_WITH_VERSION"

val mediampFfmpegAppleRuntime by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    add(
        mediampFfmpegAppleRuntime.name,
        "org.openani.mediamp:mediamp-ffmpeg-runtime-ios-xcframework:$mediampFfmpegVersion@zip",
    )
}

val extractMediampFfmpegAppleRuntime by tasks.registering(Sync::class) {
    from({ zipTree(mediampFfmpegAppleRuntime.singleFile) })
    into(layout.buildDirectory.dir("mediamp-ffmpeg/apple-runtime"))
}

val syncMediampFfmpegAppleRuntimeForXcode by tasks.registering(Sync::class) {
    dependsOn(extractMediampFfmpegAppleRuntime)
    from(layout.buildDirectory.dir("mediamp-ffmpeg/apple-runtime/MediampFFmpegKit.xcframework"))
    into(layout.projectDirectory.dir("../iosApp/Frameworks/MediampFFmpegKit.xcframework"))
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    targets.withType(KotlinNativeTarget::class.java)
        .matching { target -> target.name == "iosArm64" || target.name == "iosSimulatorArm64" }
        .configureEach {
            val capitalizedTargetName = name.replaceFirstChar { it.uppercase() }
            val sliceName = when (name) {
                "iosArm64" -> "ios-arm64"
                "iosSimulatorArm64" -> "ios-arm64-simulator"
                else -> error("Unsupported Apple target: $name")
            }
            val frameworkSearchPath = layout.buildDirectory.dir(
                "mediamp-ffmpeg/apple-runtime/MediampFFmpegKit.xcframework/$sliceName",
            )
            val frameworkSearchPathValue = frameworkSearchPath.get().asFile.absolutePath

            compilations.getByName("main").cinterops.create("mediampffmpegkit") {
                defFile(project.file("src/nativeInterop/cinterop/mediamp_ffmpegkit.def"))
                compilerOpts("-F$frameworkSearchPathValue")
            }
            binaries.configureEach {
                linkerOpts("-F$frameworkSearchPathValue", "-framework", "MediampFFmpegKit")
            }

            tasks.named("cinteropMediampffmpegkit$capitalizedTargetName") {
                dependsOn(extractMediampFfmpegAppleRuntime)
            }
            tasks.matching { task ->
                task.name == "compileKotlin$capitalizedTargetName" ||
                    (task.name.startsWith("link") && task.name.endsWith(capitalizedTargetName))
            }.configureEach {
                dependsOn(extractMediampFfmpegAppleRuntime)
            }
        }
}
```

What this wiring does:

- resolves the published Apple runtime zip
- extracts `MediampFFmpegKit.xcframework`
- selects the correct slice per Kotlin/Native target
- adds `-F...` and `-framework MediampFFmpegKit`
- forces `cinterop` and link tasks to wait for unzip

## CInterop Definition

The consumer also needs a `.def` file, for example:

`shared/src/nativeInterop/cinterop/mediamp_ffmpegkit.def`

```properties
language = Objective-C
modules = MediampFFmpegKit
package = org.openani.mediamp.ffmpeg.cinterop
```

The generated bindings expose the public C API from `MediampFFmpegKit.framework`.

## Xcode App Wiring

Kotlin/Native linking is only half of the job. The final iOS app must also embed the framework.

In Xcode:

1. Add `iosApp/Frameworks/MediampFFmpegKit.xcframework` to the app target.
2. In `Frameworks, Libraries, and Embedded Content`, set it to `Embed & Sign`.
3. Ensure the app build runs after `syncMediampFfmpegAppleRuntimeForXcode`, or run that task manually before opening/building in Xcode.

If your build uses a custom Xcode script, that script should:

1. resolve or prepare the Apple runtime
2. sync it into a stable project path
3. build the app

## Recommended Consumer Sequence

For a fresh setup:

1. Add `org.openani.mediamp:mediamp-ffmpeg:<version>` to `commonMain`.
2. Add the Apple zip dependency and unzip task in the shared module.
3. Add the `mediamp_ffmpegkit.def` file.
4. Configure `cinterop` and linker flags for `iosArm64` and `iosSimulatorArm64`.
5. Sync `MediampFFmpegKit.xcframework` into the iOS app directory.
6. Add the `xcframework` to the Xcode app target with `Embed & Sign`.

## Failure Modes

Common mistakes:

- depending on the Apple zip with `implementation(...)` and expecting it to work automatically
- pointing `cinterop` at the outer `.xcframework` directory instead of the target slice directory
- linking in Kotlin/Native but forgetting to embed the framework in the iOS app
- embedding a different copy/version than the one used by Kotlin/Native at compile time
- trying to build Intel simulator binaries even though only `ios-arm64-simulator` is published

## Future Direction

This manual flow exists because the repository does not yet ship a consumer-side Gradle plugin.

A future plugin should automate:

- resolving the Apple zip
- unpacking the `xcframework`
- selecting the correct slice per target
- wiring `cinterop`
- optionally syncing the framework for Xcode
