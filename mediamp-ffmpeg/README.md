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

iOS runtime:

```bash
./gradlew :mediamp-ffmpeg:ffmpegCreateAppleXcframework
```

Published Apple runtime artifact:

```text
org.openani.mediamp:mediamp-ffmpeg-runtime-ios-xcframework:<version>@zip
```

The Apple runtime is built as `MediampFFmpegKit.xcframework` and published as a Maven `zip` artifact. This repository wires Kotlin/Native cinterop against the locally generated framework for the iOS targets in `mediamp-ffmpeg`. Automatic external consumption is not finalized yet, so the published zip is meant for manual resolution or a future consumer plugin rather than a direct `implementation(...)` dependency.

For consumer-side wiring of the published Apple runtime, see [ios-embed-framework.md](ios-embed-framework.md).

## What gets published

`mediamp-ffmpeg` gives you the cross-platform Kotlin API.

Platform runtime behavior:

- Android: native FFmpeg binaries are packaged into the AAR and then into the APK.
- Desktop JVM: FFmpeg binaries are packaged in a separate runtime JAR.
- iOS: FFmpeg is linked into a locally generated Apple XCFramework and invoked through Kotlin/Native cinterop.

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
```

Receive FFmpeg logs via `av_log_set_callback`:

```kotlin
FFmpegKit.setLogHandler { message ->
    println("ffmpeg[level=${message.level}] ${message.line}")
}
```

`FFmpegResult` contains:

- `exitCode`
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

- `FFmpegKit.initialize(context)` must be called before `execute`.
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

```kotlin
val result = FFmpegKit().execute(listOf("-hide_banner", "-version"))
```

Build the Apple runtime before compiling or linking the iOS targets:

```bash
./gradlew :mediamp-ffmpeg:ffmpegCreateAppleXcframework
```

This produces `build/apple-xcframework/MediampFFmpegKit.xcframework` from two generated slices:

- `ios-arm64`
- `ios-arm64-simulator`

Repository-local behavior:

- `mediamp-ffmpeg` configures Kotlin/Native cinterop against the generated `MediampFFmpegKit.framework` slices.
- `FFmpegKit` calls `ffmpegkit_execute` and `ffmpegkit_set_log_callback` directly through cinterop.
- `FFmpegKit.initialize(path)` is kept for source compatibility on iOS, but it is now a no-op.

The old Apple runtime JAR model and loose `.dylib` embedding flow are no longer used by this module.

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
// Repository-local Apple flow:
// 1. Build the Apple runtime
//    ./gradlew :mediamp-ffmpeg:ffmpegCreateAppleXcframework
// 2. Compile/link the iOS targets in this repo
// 3. Embed MediampFFmpegKit.xcframework if you wrap this module in an Xcode app
```

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

- Apple runtime is published as an XCFramework zip, but external automatic consumption is not finalized yet; consumers still need their own unzip and cinterop wiring.
- Consumer-side Apple wiring is documented in [ios-embed-framework.md](ios-embed-framework.md), but it is still a manual integration flow.
- Desktop runtime resolution may need an explicit platform runtime dependency in non-standard Gradle setups.
- This wrapper currently exposes the FFmpeg CLI model, not a typed transcoding API.
