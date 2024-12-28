# MediaMP

MediaMP is a Kotlin-first media player for Compose Multiplatform. It is an
wrapper over popular media player libraries like ExoPlayer on each platform.

The goal is to provide a media player that is provides both common and backend-specific features, as
well as supporting direct access with the underlying media player library for advanced use cases.

Supported targets and backends:

|    Platform    | Architecture(s) | Implementation |
|:--------------:|-----------------|----------------|
|    Android     | Any             | ExoPlayer      |
| JVM on Windows | x86_64          | VLC            |
|  JVM on macOS  | x86_64, AArch64 | VLC            |

Platforms that are not listed above are not supported yet.

> [!NOTE]
>
> This is a work in progress. We have a working implementation for the listed platforms
> in [Animeko,](https://github.com/open-ani/Animeko) and we are working on extracting the core media
> player logic into this separate library.
>
> We are also working on porting libmpv as a more robust and feature-rich backend than VLC.

## Installation

Check the latest
version: [![Maven Central](https://img.shields.io/maven-central/v/org.openani.mediamp/mediamp-core)](https://img.shields.io/maven-central/v/org.openani.mediamp/mediamp-core)

### Kotlin Multiplatform

> [!NOTE]
>
> This is provisional. We are still working on publishing the library, especially for the backend

```kotlin
kotlin {
    val mediampVersion = "0.1.0" // Replace with the latest version
    sourceSets.commonMain.dependencies {
        implementation("org.openani.mediamp:mediamp-core:$mediampVersion") // for data-layer, does not depend on Compose

        implementation("org.openani.mediamp:mediamp-compose:$mediampVersion") // for Compose UI
    }
    sourceSets.androidMain.dependencies {
        implementation("org.openani.mediamp:mediamp-backend-exoplayer:$mediampVersion")
    }
    sourceSets.jvmMain.dependencies { // Desktop JVM
        implementation("org.openani.mediamp:mediamp-backend-vlc:$mediampVersion")
    }
}
```

### Gradle Version Catalogs

```toml
[versions]
mediamp = "0.1.0" # Replace with the latest version

[libraries]
mediamp-core = { group = "org.openani.mediamp", module = "mediamp-core", version.ref = "mediamp" }
mediamp-compose = { group = "org.openani.mediamp", module = "mediamp-compose", version.ref = "mediamp" }
mediamp-backend-exoplayer = { group = "org.openani.mediamp", module = "mediamp-backend-exoplayer", version.ref = "mediamp" }
mediamp-backend-vlc = { group = "org.openani.mediamp", module = "mediamp-backend-vlc", version.ref = "mediamp" }
```

```kotlin
kotlin {
    sourceSets.commonMain.dependencies {
        implementation(libs.mediamp.core) // for data-layer, does not depend on Compose
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
    MediaPlayer(player, Modifier.fillMaxSize())
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
