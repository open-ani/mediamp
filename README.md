# MediaMP

MediaMP is a media player for Compose Multiplatform. It is an
wrapper over popular media player libraries like ExoPlayer on each platform.

The goal is to provide a **unified** media player abstraction for
`commonMain`, as
well as supporting backend-specific features and direct access with the underlying media player
library for advanced use cases.

Supported targets and backends:

|    Platform    | Architecture(s) | Implementation |
|:--------------:|-----------------|----------------|
|    Android     | Any             | ExoPlayer      |
| JVM on Windows | x86_64          | VLC            |
|  JVM on macOS  | x86_64, AArch64 | VLC            |

Platforms that are not listed above are not supported yet. Feel free to file an issue if you need
them.

A unified MPV backend is in active development, and will be available soon.

> [!WARNING]
>
> **This is a work in progress.**
>
> No API/ABI guarantees are provided before `v0.1.0` release, but we would still like to hear your
> feedback. Please open an issue if you have any suggestions or find any bugs.

## Quick Installation

The latest
version
is: [![Maven Central](https://img.shields.io/maven-central/v/org.openani.mediamp/mediamp-api)](https://img.shields.io/maven-central/v/org.openani.mediamp/mediamp-api)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("mediampLibs") {
            from("org.openani.mediamp:catalog:0.0.4") // replace with the latest version
        }
    }
}

// build.gradle.kts
kotlin {
    sourceSets.commonMain.dependencies {
        implementation(mediampLibs.api) // Does not depend on Compose. Can be used in data/domain-layer
        implementation(mediampLibs.compose) // In UI-layer
    }
    sourceSets.androidMain.dependencies {
        implementation(mediampLibs.exoplayer) // To use ExoPlayer on Android

        // If needed, include additional ExoPlayer libraries
        implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
        implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    }
    sourceSets.jvmMain.dependencies {
        // See below
    }
}
```

- Using VLC on desktop JVM: [mediamp-vlc/README.md](mediamp-vlc/README.md)

Follow the detailed guide below if you need more information.

## Detailed Installation

The latest
version
is: [![Maven Central](https://img.shields.io/maven-central/v/org.openani.mediamp/mediamp-api)](https://img.shields.io/maven-central/v/org.openani.mediamp/mediamp-api)

### 1. Add Version Catalog

In `settings.gradle.kts`, add:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("mediampLibs") {
            from("org.openani.mediamp:catalog:0.0.4") // replace with the latest version
        }
    }
}
```

After adding the version catalog, please re-sync the project in the IDE to get completion support
for the catalog `mediampLibs`.

The catalog provides all the libraries you can use in your project:

- `mediampLibsLibs.api`: The common API for MediaMP. It does not depend on Compose, and thus can be
  used in non-UI modules.
- `mediampLibsLibs.compose`: Common Compose UI entrypoint like the `MediampPlayer` composable. It
  does not work alone, and requires a backend.

The catalog also provides accesses to backends.

- `mediampLibsLibs.exoplayer`: ExoPlayer backend for Android.
- `mediampLibsLibs.vlc`: VLC backend for JVM.

### 2. Add API dependencies

#### For multi-module apps

For app that is architected in a multi-module way, i.e. with separate modules for data/domain and
UI:

- Add `mediampLibs.api` to the data/domain layer, and
- Add `mediampLibs.compose` to the UI layer.

```kotlin
// data-layer/build.gradle.kts
kotlin {
    sourceSets.commonMain.dependencies {
        implementation(mediampLibs.api)
    }
}

// ui-layer/build.gradle.kts
kotlin {
    sourceSets.commonMain.dependencies {
        implementation(mediampLibs.compose)
    }
}
```

#### For single-module apps

For single-module apps, add both to the same module:

```kotlin
// app/build.gradle.kts
kotlin {
    sourceSets.commonMain.dependencies {
        implementation(mediampLibs.api)
        implementation(mediampLibs.compose)
    }
}
```

### 3. Add Backend dependencies

Mediamp supports different backends for different platforms.
No backends are included by default, and a backend must be manually selected for each platform.
The currently recommended backends are ExoPlayer for Android, and VLC for JVM.

```kotlin
kotlin {
    sourceSets.androidMain.dependencies {
        implementation(mediampLibs.exoplayer)

        // If needed, include additional ExoPlayer libraries
        implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
        implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    }
    sourceSets.jvmMain.dependencies { // Desktop JVM
        // See below
    }
}
```

- Using VLC on desktop JVM: [mediamp-vlc/README.md](mediamp-vlc/README.md)

## Usage

```kotlin
fun main() = singleWindowApplication {
    val player = rememberMediampPlayer()
    Column {
        Button(onClick = {
            player.playUrl("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WhatCarCanYouGetForAGrand.mp4")
        }) {
            Text("Play")
        }

        MediampPlayerSurface(player, Modifier.fillMaxSize())
    }
}
```

## License

MediaMP is mainly licensed under the Apache License version 2. However, depending on the license of
transitive dependencies, the backend-specific implementations may have different licenses.

A breakdown of the licenses:

- mediamp-exoplayer: Apache License 2.0 (Apache-v2)
- mediamp-vlc: GNU GENERAL PUBLIC LICENSE Version 3 (GPLv3)
- mediamp-mpv: Apache License 2.0
- All other modules: Apache License 2.0

You can find the full license text of Apache-v2 in the `LICENSE` file from the root of the
repository, and that of GPLv3 from `mediamp-vlc/LICENSE`.

