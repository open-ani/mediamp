# MediaMP

MediaMP is a media player for Compose Multiplatform. It is a
wrapper over popular media player libraries like ExoPlayer on each platform.

The goal is to provide a **unified** media player abstraction for
`commonMain`, as
well as supporting backend-specific features and direct access with the underlying media player
library for advanced use cases.

Supported targets and backends:

|    Platform    | Architecture(s) | Implementation |
|:--------------:|-----------------|----------------|
|    Android     | Any             | ExoPlayer      |
| JVM on Windows | x86_64, AArch64 | MPV            |
|  JVM on macOS  | x86_64, AArch64 | MPV            |
|  JVM on Linux  | x86_64          | MPV            |
|      iOS       | AArch64         | AVKit          |
| Browser (wasm) | Any             | HTMLVideoElement |

Platforms that are not listed above are not supported yet. Feel free to file an issue if you need
them.

The VLC backend is deprecated and no longer maintained; MPV replaced it as the desktop backend
in state spec v2.

> [!WARNING]
>
> **Pre-1.0**: minor releases may contain breaking API changes; they are called out in the release
> notes. Please open an issue if you have any suggestions or find any bugs.

## Installation

The latest version
is: [![Maven Central](https://img.shields.io/maven-central/v/org.openani.mediamp/mediamp-api)](https://img.shields.io/maven-central/v/org.openani.mediamp/mediamp-api)

### Version Catalogs

```toml
[versions]
# Replace with the latest version
mediamp = "0.3.0"

[libraries]
mediamp-all = { module = "org.openani.mediamp:mediamp-all", version.ref = "mediamp" }
```

```kotlin
dependencies {
    commonMainApi(libs.mediamp.all)
}
```

The `-all` bundle includes:

- Mediamp common APIs and Compose UI APIs
- ExoPlayer backend for Android
    - With `media3-exoplayer-hls` for streaming `.m3u8`
- MPV backend for JVM (desktop)
- AVKit backend for iOS
- Browser player for Compose Web / `wasmJs`

> [!WARNING]
> **Compatibility Warning**
>
> `-all` bundle exposes transitive dependencies on recommend backends.
> If, in the future, we develop a new backend and believe it is a better choice, the `-all` may be
> updated to the new backend. This should generally be fine unless your app accesses
> low-level APIs. Be mindful of this when updating `-all` bundles to newer versions.

### One-liner

```kotlin
dependencies {
    // Replace with the latest version
    commonMainApi("org.openani.mediamp:mediamp-all:0.3.0")
}
```

> [!TIP]
> For multi-module projects, consider detailed
> installation: [Detailed Installation](docs/detailed-installation.md).

## Supported Media Formats

Format support is determined by the backend on each platform.

### Desktop JVM (MPV) — Windows, macOS, Linux

The desktop backend bundles its own mpv and FFmpeg build, so the format list is fixed and
identical across desktop platforms:

- **Containers**: MP4/MOV, Matroska (MKV/WebM), MPEG-TS (incl. LATM/LOAS AAC broadcast streams),
  AVI, ASF/WMV, RealMedia (RM/RMVB), MP3/FLAC/Ogg/AAC audio files, and HLS
  (`http(s)` streaming and local playlists, incl. AES-encrypted)
- **Video**: H.264, HEVC, AV1, VP9, MPEG-4 ASP (DivX/XviD), MPEG-2, VC-1, WMV 1/2/3,
  RealVideo 3/4
- **Audio**: AAC (incl. LATM), MP3, MP2, Opus, Vorbis, FLAC, AC-3, E-AC-3,
  DTS (incl. DTS-HD MA), TrueHD, ALAC, WMA 1/2/Pro, RealAudio (Cook/Sipr/ATRAC3),
  PCM/LPCM (incl. Blu-ray), ADPCM
- **Subtitles**: ASS/SSA, SRT/SubRip, WebVTT, MP4 timed text, PGS, VobSub/DVD, SAMI, MicroDVD,
  plain text — both embedded tracks and external files
- **Hardware decoding**: D3D11VA (Windows), VideoToolbox (macOS), VAAPI (Linux);
  AV1 additionally ships with dav1d for software fallback

### Android (ExoPlayer)

Containers (MP4, MKV/WebM, MPEG-TS, Ogg, FLAC, WAV) and HLS are handled by media3.
Video/audio codec availability is device-dependent (`MediaCodec`): H.264 universally,
HEVC/VP9/AV1 on devices shipping the decoders. Subtitles: SRT, SSA/ASS (basic styling), WebVTT,
TTML, PGS, VobSub.

### iOS (AVKit)

Apple-native formats: MP4/MOV/M4V containers and HLS streams; H.264/HEVC (AV1 on devices with
hardware decode); AAC/ALAC/AC-3/E-AC-3 audio; WebVTT subtitles. Matroska and ASS subtitles are
not supported by AVFoundation.

### Browser (wasm)

Whatever the user's browser can play through `HTMLMediaElement` — typically MP4 (H.264, often
HEVC/AV1/VP9) and WebM; HLS natively on Safari only.

## Usage

### Streaming Video

```kotlin
fun main() = singleWindowApplication {
    val player = rememberMediampPlayer()
    val scope = rememberCoroutineScope()
    Column {
        Button(onClick = {
            scope.launch {
                player.playUri("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WhatCarCanYouGetForAGrand.mp4")
            }
        }) {
            Text("Play")
        }

        MediampPlayerSurface(player, Modifier.fillMaxSize())
    }
}
```

### Observing Playback State

The player state is an atomic snapshot [`PlayerState`](mediamp-api/src/commonMain/kotlin/PlayerState.kt)
of three orthogonal axes, observed via `player.state` (spec: `docs/playback-state-v2.md`):

```kotlin
val state: PlayerState = player.state.value
state.mediaStatus   // lifecycle: Idle / Opening / Ready / Ended / Error / Released
state.playWhenReady // play/pause intent — drive the play/pause button icon with this
state.isBuffering   // data availability — show a spinner when state.isLoadingOrBuffering
```

```kotlin
// Play/pause button: never dead, no flicker during buffering.
Button(onClick = { player.togglePlayWhenReady() }) {
    Icon(if (state.playWhenReady) PauseIcon else PlayIcon)
}

// Session-advancing reactions (e.g. auto-play-next) use events, not state:
player.events.filterIsInstance<PlaybackEvent.MediaEnded>().collect { playNextEpisode() }
```

### Accessing Player Features in commonMain

#### Adjust Playback Speed

```kotlin
val player = rememberMediampPlayer()
LaunchedEffect(player) {
    player.playUri("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WhatCarCanYouGetForAGrand.mp4")
}
Column {
    Button(onClick = {
        player.features[PlaybackSpeed]?.set(2.0f) // `null` means the platform does not support this feature
    }) {
        Text("Speed up to 2x")
    }

    MediampPlayerSurface(player, Modifier.fillMaxSize())
}
```

### Unit Testing

> [!NOTE]
> The unit testing API is **experimental** and will be changed in the future. Use at your own risk.

Add dependency:

```toml
[libraries]
mediamp-test = { module = "org.openani.mediamp:mediamp-test", version.ref = "mediamp" }
```

```kotlin
dependencies {
    commonTestApi(libs.mediamp.test)
}
```

A scriptable player `TestMediampPlayer` is provided for unit testing.
It runs the same state machine (and follows the same specification, `docs/playback-state-v2.md`)
as the real players, backed by a fake native transport that you drive from the test: control how
opens complete (`openBehavior`), and inject native facts (`injectStall`, `injectEnded`,
`injectError`, `injectExternalPlayWhenReady`, `injectPosition`, `injectProperties`).

```kotlin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class MyTest {
    @Test
    fun test() = runTest {
        val player = TestMediampPlayer(StandardTestDispatcher(testScheduler))

        // Will not actually make network requests. playUri defaults to playWhenReady = true.
        player.playUri("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WhatCarCanYouGetForAGrand.mp4")
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
        assertTrue(player.state.value.isPlaying)

        player.injectPosition(1000L) // The fake playback clock is driven by the test
        advanceUntilIdle()           // Let the state machine process the injected fact
        assertEquals(1000L, player.currentPositionMillis.value)

        player.injectStall(true)     // Simulate a mid-playback buffering stall
        advanceUntilIdle()
        assertTrue(player.state.value.isBuffering)
        assertTrue(player.state.value.playWhenReady) // Buffering does not change the play intent
    }
}
```

## Advanced Usages

### Custom Media Data

```kotlin
fun main() = singleWindowApplication {
    val player = rememberMediampPlayer()
    val scope = rememberCoroutineScope()

    Column {
        Button(onClick = {
            scope.launch {
                player.setMediaData(createMediaData(), playWhenReady = true)
            }
        }) {
            Text("Play")
        }

        MediampPlayerSurface(player, Modifier.fillMaxSize())
    }
}

fun createMediaData(): SeekableInputMediaData {
    // Implement SeekableInputMediaData. 
    // It's like implementing a kotlinx-io Input with random-access seeking.
}
```

If you use kotlinx-io, you might consider the `BufferedSeekableInput` provided by
`mediamp-source-ktxio` in helping the
custom implementation of I/O operations:

```toml
[libraries]
mediamp-source-ktxio = { module = "org.openani.mediamp:mediamp-source-ktxio", version.ref = "mediamp" }
```

```kotlin
dependencies {
    commonMainApi(libs.mediamp.source.ktxio)
}
```

### Obtaining the Platform Player

Access the underlying Android `ExoPlayer`, desktop `MPVHandle` and iOS `AVPlayer` for
advanced use cases.

```kotlin
// On Android
val player = ExoPlayerMediampPlayer()
val platform: ExoPlayer = player.impl
```

```kotlin
// On iOS
val player = AVKitMediampPlayer()
val platform: AVPlayer = player.impl
```

```kotlin
// On Desktop
val player = MpvMediampPlayer(...)
val platform: MPVHandle = player.impl
```

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val player: MediampPlayer = rememberMediampPlayer()
            Column {
                Button(onClick = {
                    Toast.makeText(
                        this@MainActivity,
                        "The backend is ${player.impl as ExoPlayer}!",
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Text("Play")
                }

                MediampPlayerSurface(player, Modifier.fillMaxSize())
            }
        }
    }
}
```

## License

MediaMP is mainly licensed under the Apache License version 2. However, depending on the license of
transitive dependencies, the backend-specific implementations may have different licenses.

A breakdown of the licenses:

- mediamp-exoplayer: Apache License 2.0 (Apache-v2)
- mediamp-mpv: Apache License 2.0
- All other published modules: Apache License 2.0

The deprecated, no-longer-published mediamp-vlc sources remain GPLv3 (`mediamp-vlc/LICENSE`).
You can find the full license text of Apache-v2 in the `LICENSE` file from the root of the
repository.
