# Detailed Installation

## Multi-module Installation

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
            from("org.openani.mediamp:catalog:0.0.23") // replace with the latest version
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
        implementation(mediampLibs.exoplayer)
        implementation(mediampLibs.exoplayer.compose)

        // If needed, include additional ExoPlayer libraries for streaming. No configuration required.
        implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
        implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    }
    sourceSets.jvmMain.dependencies {
        implementation(mediampLibs.vlc)
        implementation(mediampLibs.vlc.compose)
        // VLC must be installed on user OS. See below for shipping VLC binaries with your app.
    }
    sourceSets.iosMain.dependencies {
        implementation(mediampLibs.avkit)
        implementation(mediampLibs.avkit.compose)
    }
}
```

- Shipping VLC binaries: [mediamp-vlc/README.md](../mediamp-vlc/README.md)

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
            from("org.openani.mediamp:catalog:0.0.23") // replace with the latest version
        }
    }
}
```

After adding the version catalog, please re-sync the project in the IDE to get completion support
for the catalog `mediampLibs`.

The catalog provides all the libraries you can use in your project:

- `mediampLibs.api`: The common API for MediaMP. It does not depend on Compose, and thus can be
  used in non-UI modules.
- `mediampLibs.compose`: Common Compose UI entrypoint like the `MediampPlayer` composable. It
  does not work alone, and requires a backend.
- `mediampLibs.exoplayer`: ExoPlayer backend for Android.
- `mediampLibs.exoplayer.compose`: Compose support for ExoPlayer
- `mediampLibs.vlc`: VLC backend for JVM.
- `mediampLibs.vlc.compose`: Compose support for VLC.
- `mediampLibs.avkit`: AVKit backend for iOS.
- `mediampLibs.avkit.compose`: Compose support for AVKit.

### 2. Add API dependencies

#### For multi-module apps

For apps that are architected in a multi-module way, i.e. with separate modules for data/domain and
UI, you may:

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

        // If needed, include additional ExoPlayer libraries for streaming. No configuration required.
        implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
        implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    }
    sourceSets.jvmMain.dependencies { // Desktop JVM
        implementation(mediampLibs.vlc)
        implementation(mediampLibs.vlc.compose)
        // VLC must be installed on user OS. See below for shipping VLC binaries with your app.
    }
    sourceSets.iosMain.dependencies {
        implementation(mediampLibs.avkit)
        implementation(mediampLibs.avkit.compose)
    }
}
```

- Using VLC on desktop JVM: [mediamp-vlc/README.md](../mediamp-vlc/README.md)
