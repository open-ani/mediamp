# MediaMP VLC backend

[VLC]: https://www.videolan.org/vlc/

[vlcj]: https://github.com/caprica/vlcj

This module provides a [VLC][VLC] backend for MediaMP using [vlcj][vlcj].

Supported platforms: desktop JVM on macOS (AArch64, X86_64), Windows (X86_64), and Linux (X86_64).

## Installation

Add the following to your `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets.commonMain.dependencies {
        implementation(mediampLibs.vlc) // Also requires binaries, see below
    }
}
```

### Installing binaries

The VLC backend requires the VLC binaries at runtime. Binaries must be prepared manually.

[appResources]: https://

We recommend shipping the VLC binaries with your application using Compose `appResources`.

1. Determine which platforms you need to support.
   Currently, the supported platforms are macOS (AArch64, X86_64) and Windows (X86_64).
2. Download the VLC binaries for each platform from
   the [official website](https://www.videolan.org/vlc/).
