# mediamp-ffmpeg

`mediamp-ffmpeg` is a Kotlin Multiplatform wrapper around the FFmpeg command-line tool.

The published artifacts provide a common `FFmpegKit` API, but the runtime packaging behavior differs by platform.

## Coordinates

Core API:

```kotlin
implementation("org.openani.mediamp:mediamp-ffmpeg:<version>")
```

Desktop runtime artifacts:

```kotlin
runtimeOnly("org.openani.mediamp:mediamp-ffmpeg-runtime-macos-arm64:<version>")
runtimeOnly("org.openani.mediamp:mediamp-ffmpeg-runtime-macos-x64:<version>")
runtimeOnly("org.openani.mediamp:mediamp-ffmpeg-runtime-linux-x64:<version>")
runtimeOnly("org.openani.mediamp:mediamp-ffmpeg-runtime-windows-x64:<version>")
```

If your desktop Gradle variant resolution is configured correctly, the matching runtime JAR may be selected automatically. If runtime extraction fails at startup, add the platform-specific runtime dependency explicitly.

iOS runtime artifacts:

```kotlin
implementation("org.openani.mediamp:mediamp-ffmpeg-runtime-ios-arm64:<version>")
implementation("org.openani.mediamp:mediamp-ffmpeg-runtime-ios-simulator-arm64:<version>")
```

These iOS runtime artifacts are published separately from the Kotlin/Native `klib`. They contain the Apple FFmpeg binaries that must be embedded into the final app bundle.

## What gets published

`mediamp-ffmpeg` gives you the cross-platform Kotlin API.

Platform runtime behavior:

- Android: native FFmpeg binaries are packaged into the AAR and then into the APK.
- Desktop JVM: FFmpeg binaries are packaged in a separate runtime JAR.
- iOS: the Kotlin API and Apple runtime artifacts are both published, but the app build must still unpack and embed the runtime files into the app bundle.

## API

Main entry point:

```kotlin
import org.openani.mediamp.ffmpeg.FFmpegKit

val kit = FFmpegKit()
```

Run a command and wait for completion:

```kotlin
val result = kit.execute(
    listOf("-hide_banner", "-version"),
)

println(result.exitCode)
println(result.isSuccess)
println(result.stdout)
println(result.stderr)
```

Stream output line by line:

```kotlin
kit.executeStreaming(listOf("-hide_banner", "-version")).collect { line ->
    if (line.isError) {
        println("ERR: ${line.line}")
    } else {
        println("OUT: ${line.line}")
    }
}
```

`FFmpegResult` contains:

- `exitCode`
- `stdout`
- `stderr`
- `isSuccess`

## Android usage

Android requires one-time initialization before the first FFmpeg call:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FFmpegKit.initialize(this)
    }
}
```

Then use it normally:

```kotlin
val result = FFmpegKit().execute(listOf("-hide_banner", "-version"))
```

Notes:

- `FFmpegKit.initialize(context)` must be called before `execute` or `executeStreaming`.
- The Android runtime is loaded from `nativeLibraryDir`.
- `LD_LIBRARY_PATH` is set internally by the library. Consumers do not need to set it manually.

## Desktop JVM usage

Desktop does not require explicit initialization.

```kotlin
val result = FFmpegKit().execute(listOf("-hide_banner", "-version"))
```

Runtime behavior:

- The library extracts FFmpeg binaries from the runtime JAR on first use.
- On macOS and Linux it sets `DYLD_LIBRARY_PATH` or `LD_LIBRARY_PATH` for the child process.
- On Windows it prepends the extraction directory to `PATH`.

If you see an error like `ffmpeg-natives.txt not found on classpath`, the platform runtime JAR is missing from the runtime classpath.

## iOS usage

iOS does not require an explicit `initialize(...)` call.

```kotlin
val result = FFmpegKit().execute(listOf("-hide_banner", "-version"))
```

The published Kotlin artifact does not automatically embed the Apple FFmpeg runtime into the final app bundle.

You still need two things:

1. Add the matching Apple runtime artifact as a dependency for the target you build.
2. Unpack that artifact during your app build and copy its runtime files into the app bundle resources.

Recommended runtime coordinates:

- real device: `org.openani.mediamp:mediamp-ffmpeg-runtime-ios-arm64:<version>`
- arm64 simulator: `org.openani.mediamp:mediamp-ffmpeg-runtime-ios-simulator-arm64:<version>`

The iOS `klib` metadata now carries a dependency constraint pointing at the matching runtime coordinate, but that only helps dependency resolution. It does not perform bundle embedding for you.

At minimum, the app bundle must contain:

- `ffmpeg`
- `libffmpegkitcmd.dylib`
- the dependent FFmpeg dylibs such as `libavcodec*.dylib`, `libavformat*.dylib`, `libavutil*.dylib`, `libswresample*.dylib`, `libswscale*.dylib`

Runtime expectations:

- `FFmpegKit` looks up those files from `NSBundle.mainBundle.resourcePath`.
- The library configures `DYLD_LIBRARY_PATH` internally.
- The wrapper entrypoint is `ffmpegkit_execute` inside `libffmpegkitcmd.dylib`.

If the runtime files are not embedded, execution will fail with errors like `libffmpegkitcmd.dylib not found in app bundle resource path` or `dlopen(...) failed`.

## Recommended dependency patterns

KMP shared module:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("org.openani.mediamp:mediamp-ffmpeg:<version>")
        }
    }
}
```

iOS app:

```kotlin
kotlin {
    sourceSets {
        iosArm64Main.dependencies {
            implementation("org.openani.mediamp:mediamp-ffmpeg:<version>")
            implementation("org.openani.mediamp:mediamp-ffmpeg-runtime-ios-arm64:<version>")
        }
        iosSimulatorArm64Main.dependencies {
            implementation("org.openani.mediamp:mediamp-ffmpeg:<version>")
            implementation("org.openani.mediamp:mediamp-ffmpeg-runtime-ios-simulator-arm64:<version>")
        }
    }
}
```

Then make your Xcode or Gradle-for-iOS packaging step unzip that runtime artifact and copy the contained `ffmpeg` and `.dylib` files into the app bundle resources.

Android app:

```kotlin
dependencies {
    implementation("org.openani.mediamp:mediamp-ffmpeg:<version>")
}
```

Desktop JVM app with explicit runtime:

```kotlin
dependencies {
    implementation("org.openani.mediamp:mediamp-ffmpeg:<version>")
    runtimeOnly("org.openani.mediamp:mediamp-ffmpeg-runtime-macos-arm64:<version>")
}
```

## Known limitations

- Apple runtime embedding is still not automated. The runtime artifacts are published, but app bundling must unpack and embed them.
- Desktop runtime resolution may need an explicit platform runtime dependency in non-standard Gradle setups.
- This wrapper currently exposes the FFmpeg CLI model, not a typed transcoding API.
