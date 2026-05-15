# MediaMP Web Preview

This module is a browser-only wasm preview app for MediaMP. It demonstrates how
to render `WebMediampPlayer` in Compose for Web and how to control playback with
the common MediaMP APIs.

## Run the Preview

From the repository root:

```bash
./gradlew :mediamp-web-preview:wasmJsBrowserDevelopmentRun
```

Then open:

```text
http://localhost:8080/
```

The preview loads a public MP4 and WebVTT subtitle by default. You can replace
both URLs in the page and click `Load` to test another media source.

## Consumer Usage

Add the MediaMP API dependency to a wasm browser target:

```kotlin
kotlin {
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation("org.openani.mediamp:mediamp-api:<version>")
        }
    }
}
```

In Compose, create a player, load a URL, and render the player surface:

```kotlin
@Composable
fun VideoPlayer(url: String) {
    val player = rememberMediampPlayer()

    LaunchedEffect(url) {
        player.playUri(url)
    }

    MediampPlayerSurface(
        mediampPlayer = player,
        modifier = Modifier.fillMaxSize(),
    )
}
```

Playback can be controlled through the common `MediampPlayer` API:

```kotlin
player.resume()
player.pause()
player.seekTo(60_000)
player.stopPlayback()
```

Optional features are exposed through `player.features`:

```kotlin
player.features[AudioLevelController]?.setVolume(0.5f)
player.features[AudioLevelController]?.setMute(true)
player.features[PlaybackSpeed]?.set(1.5f)
player.features[VideoAspectRatio]?.setMode(AspectRatioMode.FIT)
```

Subtitles can be passed as `MediaExtraFiles`:

```kotlin
player.setMediaData(
    UriMediaData(
        uri = videoUrl,
        extraFiles = MediaExtraFiles(
            subtitles = listOf(
                Subtitle(
                    uri = subtitleUrl,
                    mimeType = "text/vtt",
                    language = "en",
                    label = "English",
                ),
            ),
        ),
    ),
)
```

## Browser Constraints

The wasm implementation is backed by the browser's `HTMLVideoElement`. Media
format support, CORS, Range requests, and autoplay behavior therefore follow the
current browser's rules.
