# mediamp-ffmpeg API Reference

`mediamp-ffmpeg` is a Kotlin Multiplatform binding for FFmpeg's `libav*` libraries. It exposes two interfaces:

1. **Kotlin API** (`MediaTranscoder` + `MediaOperation`) — recommended for common operations.
2. **CLI Bridge** (`FFmpegKit.execute(args)`) — fallback for advanced or unmapped features.

---

## Quick Start

### Dependency

```kotlin
implementation("org.openani.mediamp:mediamp-ffmpeg:<version>")
```

Platform runtimes:

- **Android**: native libraries are bundled in the AAR.
- **Desktop JVM**: add a runtime JAR (e.g. `mediamp-ffmpeg-runtime-macos-arm64`).
- **iOS**: build the local XCFramework first (`./gradlew :mediamp-ffmpeg:ffmpegCreateAppleXcframework`).

### Initialize (Android only)

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FFmpegKit.initialize(this)
    }
}
```

Desktop and iOS do not require explicit initialization.

---

## Kotlin API (Recommended)

### MediaTranscoder

```kotlin
val result = MediaTranscoder().execute(operation)
println(result.exitCode) // 0 on success
```

### MediaOperation

#### Remux

Copy streams from one container to another without re-encoding.

```kotlin
MediaOperation.Remux(
    input = "input.ts",
    output = "output.mp4",
    bitstreamFilters = mapOf(1 to "aac_adtstoasc"),
    movflags = listOf("faststart"),
    allowedExtensions = "ALL",
    protocolWhitelist = "file,crypto,data",
)
```

| Parameter           | Type               | Default       | Description                                                                                                            |
|---------------------|--------------------|---------------|------------------------------------------------------------------------------------------------------------------------|
| `input`             | `String`           | —             | Input file path or URL.                                                                                                |
| `output`            | `String`           | —             | Output file path.                                                                                                      |
| `bitstreamFilters`  | `Map<Int, String>` | `emptyMap()`  | Stream index → BSF name. Equivalent to `-bsf:<stream> <name>`.                                                         |
| `movflags`          | `List<String>`     | `emptyList()` | Muxer flags. Equivalent to `-movflags +flag1+flag2`.                                                                   |
| `allowedExtensions` | `String?`          | `null`        | Allowed file extensions for playlist inputs. Equivalent to `-allowed_extensions`.                                      |
| `protocolWhitelist` | `String?`          | `null`        | Whitelisted protocols. Equivalent to `-protocol_whitelist`.                                                            |
| `ignoreDts`         | `Boolean`          | `false`       | Ignore input DTS and derive from PTS. Sets `AVFMT_FLAG_IGNDTS`. Useful for HLS/MPEG-TS sources with discontinuous DTS. |

**What `Remux` does automatically:**

- Opens the input with optional format-private options.
- Reads stream info.
- Maps all input streams to output streams (`-map 0`).
- Copies codec parameters without re-encoding (`-c copy`).
- Rescales packet timestamps from input time base to output time base.
- Applies configured bitstream filters per stream.
- Writes muxer options (e.g. `movflags`) into the output header.

#### Probe

Open an input and read stream information.

```kotlin
MediaOperation.Probe(
    input = "video.mp4",
)
```

#### Transcode

> **Not yet implemented.** Calling `execute` with `MediaOperation.Transcode` throws `UnsupportedOperationException`. Use `FFmpegKit.execute(args)` as a fallback.

```kotlin
MediaOperation.Transcode(
    input = "input.mkv",
    output = "output.mp4",
    videoCodec = "libx264",
    audioCodec = "aac",
)
```

---

## CLI Bridge (Fallback)

For operations not yet covered by the Kotlin API, use `FFmpegKit.execute` with raw CLI arguments.

```kotlin
val kit = FFmpegKit()
val result = kit.execute(
    listOf(
        "-y", "-nostdin",
        "-i", "input.mp4",
        "-vf", "scale=1280:-2",
        "-c:v", "libx264",
        "-crf", "23",
        "output.mp4",
    )
)
```

### Log Handling

```kotlin
FFmpegKit.setLogHandler { message ->
    println("[${message.level}] ${message.line}")
}
```

---

## Low-Level Wrappers

The module also exposes thin wrappers around `libavformat` / `libavcodec` for consumers who need direct control.

| Class | Purpose |
|-------|---------|
| `InputContainer` | Read from `AVFormatContext`. `AutoCloseable`. |
| `OutputContainer` | Write to `AVFormatContext`. `AutoCloseable`. |
| `Stream` | Metadata for one stream (index, timeBase, codecParameters). |
| `AVPacket` | Packet buffer. `AutoCloseable`. |
| `BitstreamFilter` | BSF context per stream. `AutoCloseable`. |
| `MuxerOptions` | Mutable map wrapper for `AVDictionary` options passed to `writeHeader`. |

Example:

```kotlin
InputContainer().use { input ->
    input.open("video.mp4")
    input.findStreamInfo()
    for (stream in input.streams) {
        println("Stream ${stream.index}: ${stream.codecType}")
    }
}
```

---

## Feature Coverage Matrix

| Feature | Kotlin API | CLI Bridge | Notes |
|---------|-----------|------------|-------|
| Remux (stream copy) | Yes | Yes | Full support including BSF and muxer options. |
| Probe | Yes | Yes | `MediaOperation.Probe` reads stream info. |
| Transcode | No | Yes | `MediaOperation.Transcode` is a stub. |
| Bitstream filters | Yes | Yes | `aac_adtstoasc`, `h264_mp4toannexb`, etc. |
| Muxer options (`movflags`) | Yes | Yes | Via `MuxerOptions`. |
| Input options (`allowed_extensions`, `protocol_whitelist`) | Yes | Yes | Passed to `avformat_open_input` as `AVDictionary`. |
| Filters (`-vf`, `-af`) | No | Yes | Filtergraphs not yet wrapped. |
| Encoding parameters (`-crf`, `-b:v`, `-preset`) | No | Yes | Use CLI bridge. |
| Multi-input / complex filter | No | Yes | Use CLI bridge. |
| Subtitle burn-in / extraction | No | Yes | Use CLI bridge. |
| Hardware acceleration | No | Yes | Use CLI bridge. |

---

## Migration from CLI to Kotlin API

Before:

```kotlin
val args = listOf(
    "-y", "-nostdin",
    "-allowed_extensions", "ALL",
    "-protocol_whitelist", "file,crypto,data",
    "-i", playlistFile,
    "-map", "0", "-c", "copy",
    "-bsf:a", "aac_adtstoasc",
    "-movflags", "+faststart",
    outputFile,
)
FFmpegKit().execute(args)
```

After:

```kotlin
MediaTranscoder().execute(
    MediaOperation.Remux(
        input = playlistFile,
        output = outputFile,
        bitstreamFilters = mapOf(1 to "aac_adtstoasc"),
        movflags = listOf("faststart"),
        allowedExtensions = "ALL",
        protocolWhitelist = "file,crypto,data",
        ignoreDts = true, // HLS sources often need this
    )
)
```

---

## Platform Notes

- **Android**: JavaCPP-based JNI. `FFmpegKit.initialize(context)` is required.
- **Desktop JVM**: JavaCPP-based JNI. Binaries are extracted automatically from the runtime JAR.
- **iOS**: Kotlin/Native cinterop against a statically-linked FFmpeg framework. Build the framework first with `./gradlew :mediamp-ffmpeg:ffmpegCreateAppleXcframework`.
