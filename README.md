# MediaMP

MediaMP is a Kotlin-first media player for Compose Multiplatform. It is an
wrapper over popular media player libraries like ExoPlayer on each platform.

The goal is to provide both common and backend-specific features for `commonMain`, as
well as supporting direct access with the underlying media player library for advanced use cases.

Supported targets and backends:

|    Platform    | Architecture(s) | Implementation |
|:--------------:|-----------------|----------------|
|    Android     | Any             | ExoPlayer      |
| JVM on Windows | x86_64          | VLC            |
|  JVM on macOS  | x86_64, AArch64 | VLC            |

Platforms that are not listed above are not supported yet.

> [!WARNING]
>
> This is a work in progress. The following steps will not work for desktop JVMs.
>
> We have a working implementation for the listed platforms
> in [Animeko](https://github.com/open-ani/Animeko), and we are working on extracting the core media
> player logic into this separate library.
>
> We are also working on porting libmpv as a more robust and feature-rich backend than VLC.

## Installation

Check the latest
version: [![Maven Central](https://img.shields.io/maven-central/v/org.openani.mediamp/mediamp-api)](https://img.shields.io/maven-central/v/org.openani.mediamp/mediamp-api)

### 1. Add Version Catalogs

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

then reload the project in the IDE.

### 2. Add Dependencies

You will need to add the `libs.mediamp.api` to your data/domain layer,
`libs.mediamp.compose` to your UI layer, and choose a backend for each target platform:

```kotlin
kotlin {
    sourceSets.commonMain.dependencies {
        implementation(libs.mediamp.api) // for data-layer, does not depend on Compose
        implementation(libs.mediamp.compose) // for Compose UI
    }
    sourceSets.androidMain.dependencies {
        implementation(libs.mediamp.backend.exoplayer)
    }
    sourceSets.jvmMain.dependencies { // Desktop JVM
        implementation(libs.mediamp.backend.vlc)
    }
}
```

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

        MediaPlayer(player, Modifier.fillMaxSize())
    }
}
```

## License

MediaMP is licensed under the GNU General Public License v3.0. You can find the full license text in
the `LICENSE` file.

```
MediaMP
Copyright (C) 2024  OpenAni and contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```
