# MediaMP VLC backend

[VLC]: https://www.videolan.org/vlc/

[vlcj]: https://github.com/caprica/vlcj

This module provides a [VLC][VLC] backend for MediaMP using [vlcj][vlcj].

Supported platforms: desktop JVM on operating systems that VLC supports.

**Copyright notice**: As [vlcj][vlcj] is licensed under GPLv3, mediamp-vlc is also
licensed under GPLv3, while
all other MediaMP modules are under Apache-v2. You may want to refer to [vlcj][vlcj] for
more information. Please also check the [VLC][VLC] website if you need to distribute your app.

## Installation

Add the following to your `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets.jvmMain.dependencies { // desktop JVM only
        implementation(mediampLibs.vlc)
        implementation(mediampLibs.vlc.loader) // Automatic library loading. Requires binaries, see below
    }
}
```

The VLC backend requires the VLC binaries at runtime. Binaries must be prepared manually.

We recommend shipping the VLC binaries with your application using the appResources from Compose
Gradle plugin.

### Official VLC binaries

Mediamp only uses VLC as a library, and we don't provide prebuilt VLC binaries.
VLC binaries can be downloaded from
the [official website](https://www.videolan.org/vlc/).
A well-tested VLC version is `3.0.20`. Please consider sticking to this version for the best
compatibility.

### Automatic Library Loading (Recommended)

Mediamp VLC loader supports automatic loading of VLC binaries from the appResources directory for
both development environment and distribution. To use this feature, follow these steps:

1. Download VLC pre-built binaries for **each platform separately** into `appResources/$triple/`.
   `$triple`
   must be exactly one of: `macos-x64`, `macos-arm64`, `windows-x64`, `windows-arm64`,
   `linux-arm64`, `linux-x64`.
2. So far, your file tree should look like this:
   ```
   desktopApp/
    |- appResources/
    |  |- macos-arm64/
    |  |  |- lib/
    |  |  |  |- libvlc.dylib
    |  |  |- plugins/
    |  |  |  |- xxx.dylib
    |  |- macos-x64/
    |  |  |- lib/
    |  |  |  |- libvlc.dylib
    |  |  |- plugins/
    |  |  |  |- xxx.dylib
    |  |- windows-x64/
    |  |  |- lib/
    |  |  |  |- libvlc.dll
    |  |  |  |- plugins/
    |- src/
    |- build.gradle.kts
   ```
3. In `build.gradle.kts`, add this line to pack the resources for distribution:
   ```kotlin
   compose.desktop {
       application {
           nativeDistributions {
               appResourcesRootDir.set(file("appResources"))
           }
       }
   }
   ```
   Compose automatically selects the correct directory (e.g. `windows-x64`) based on the platform
   you are building for.
4. Now your app should work when packaged.

To make it work also in the development environment, you can
either:

- Set the `compose.application.resources.dir` system property to the `appResources`
  directory.
  This can be done in the `build.gradle.kts` file:
  ```kotlin
  tasks.withType<JavaExec> { 
      systemProperty("compose.application.resources.dir", file("appResources").absolutePath)
  }
  ```
- Or, enable `TestDiscoveryDirectoryProvider`:
  ```kotlin
  // Call this method before any Mediamp- or VLC-related code
  org.openani.mediamp.vlc.loader.MediampVlcLoader.enableTestDiscoveryDirectoryProvider()
  ```
  By default, it loads from `./appResources`. A custom path can be provided:
  ```kotlin
  org.openani.mediamp.vlc.loader.MediampVlcLoader.enableTestDiscoveryDirectoryProvider("path/to/appResources")
  ```

### Manual Library Loading

Implement the service interface
`uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider` to load the VLC library
manually.
Check the [vlcj documentation][vlcj] for more information.
