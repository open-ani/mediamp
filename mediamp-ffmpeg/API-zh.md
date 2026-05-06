# mediamp-ffmpeg API 参考文档

`mediamp-ffmpeg` 是 FFmpeg `libav*` 库的 Kotlin Multiplatform 绑定。它提供两套接口：

1. **Kotlin API**（`MediaTranscoder` + `MediaOperation`）—— 推荐用于常见操作。
2. **CLI 桥接**（`FFmpegKit.execute(args)`）—— 用于高级或未映射功能的兜底方案。

---

## 快速开始

### 依赖

```kotlin
implementation("org.openani.mediamp:mediamp-ffmpeg:<version>")
```

平台运行时：

- **Android**：原生库已打包进 AAR。
- **Desktop JVM**：需要额外添加运行时 JAR（如 `mediamp-ffmpeg-runtime-macos-arm64`）。
- **iOS**：先本地构建 XCFramework（`./gradlew :mediamp-ffmpeg:ffmpegCreateAppleXcframework`）。

### 初始化（仅 Android）

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FFmpegKit.initialize(this)
    }
}
```

Desktop 和 iOS 不需要显式初始化。

---

## Kotlin API（推荐）

### MediaTranscoder

```kotlin
val result = MediaTranscoder().execute(operation)
println(result.exitCode) // 0 表示成功
```

### MediaOperation

#### Remux（复用）

在不重新编码的情况下，将流从一个容器复制到另一个容器。

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

| 参数                  | 类型                 | 默认值           | 说明                                                                        |
|---------------------|--------------------|---------------|---------------------------------------------------------------------------|
| `input`             | `String`           | —             | 输入文件路径或 URL。                                                              |
| `output`            | `String`           | —             | 输出文件路径。                                                                   |
| `bitstreamFilters`  | `Map<Int, String>` | `emptyMap()`  | 流索引 → BSF 名称。等价于 `-bsf:<stream> <name>`。                                  |
| `movflags`          | `List<String>`     | `emptyList()` | Muxer 标志。等价于 `-movflags +flag1+flag2`。                                    |
| `allowedExtensions` | `String?`          | `null`        | 播放列表输入允许的文件扩展名。等价于 `-allowed_extensions`。                                 |
| `protocolWhitelist` | `String?`          | `null`        | 白名单协议。等价于 `-protocol_whitelist`。                                          |
| `ignoreDts`         | `Boolean`          | `false`       | 忽略输入 DTS 并从 PTS 推导。设置 `AVFMT_FLAG_IGNDTS`。适用于 HLS/MPEG-TS 源存在 DTS 不连续的情况。 |

**`Remux` 自动完成的工作：**

- 以可选的格式私有选项打开输入。
- 读取流信息。
- 将所有输入流映射到输出流（`-map 0`）。
- 复制编解码器参数而不重新编码（`-c copy`）。
- 将数据包时间戳从输入时基缩放到输出时基。
- 按流配置应用码流过滤器。
- 将 Muxer 选项（如 `movflags`）写入输出头部。

#### Probe（探测）

打开输入并读取流信息。

```kotlin
MediaOperation.Probe(
    input = "video.mp4",
)
```

#### Transcode（转码）

> **尚未实现。** 使用 `MediaOperation.Transcode` 调用 `execute` 会抛出 `UnsupportedOperationException`。请使用 `FFmpegKit.execute(args)` 作为兜底方案。

```kotlin
MediaOperation.Transcode(
    input = "input.mkv",
    output = "output.mp4",
    videoCodec = "libx264",
    audioCodec = "aac",
)
```

---

## CLI 桥接（兜底方案）

对于 Kotlin API 尚未覆盖的操作，使用 `FFmpegKit.execute` 传入原始 CLI 参数。

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

### 日志处理

```kotlin
FFmpegKit.setLogHandler { message ->
    println("[${message.level}] ${message.line}")
}
```

---

## 底层包装器

该模块还暴露了围绕 `libavformat` / `libavcodec` 的薄包装器，供需要直接控制的消费者使用。

| 类 | 用途 |
|-------|---------|
| `InputContainer` | 从 `AVFormatContext` 读取。`AutoCloseable`。 |
| `OutputContainer` | 写入 `AVFormatContext`。`AutoCloseable`。 |
| `Stream` | 单个流的元数据（索引、时基、编解码器参数）。 |
| `AVPacket` | 数据包缓冲区。`AutoCloseable`。 |
| `BitstreamFilter` | 每流的 BSF 上下文。`AutoCloseable`。 |
| `MuxerOptions` | 传递给 `writeHeader` 的 `AVDictionary` 选项的可变映射包装器。 |

示例：

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

## 功能覆盖矩阵

| 功能 | Kotlin API | CLI 桥接 | 说明 |
|---------|-----------|------------|-------|
| Remux（流复制） | 是 | 是 | 完整支持，包括 BSF 和 Muxer 选项。 |
| Probe（探测） | 是 | 是 | `MediaOperation.Probe` 读取流信息。 |
| Transcode（转码） | 否 | 是（仅 Android/Desktop） | `MediaOperation.Transcode` 是存根。 |
| 码流过滤器 | 是 | 是（仅 Android/Desktop） | `aac_adtstoasc`、`h264_mp4toannexb` 等。 |
| Muxer 选项（`movflags`） | 是 | 是（仅 Android/Desktop） | 通过 `MuxerOptions`。 |
| 输入选项（`allowed_extensions`、`protocol_whitelist`） | 是 | 是（仅 Android/Desktop） | 作为 `AVDictionary` 传递给 `avformat_open_input`。 |
| 滤镜（`-vf`、`-af`） | 否 | 是（仅 Android/Desktop） | 尚未包装 Filtergraph。 |
| 编码参数（`-crf`、`-b:v`、`-preset`） | 否 | 是（仅 Android/Desktop） | 使用 CLI 桥接。 |
| 多输入 / 复杂滤镜 | 否 | 是（仅 Android/Desktop） | 使用 CLI 桥接。 |
| 字幕烧录 / 提取 | 否 | 是（仅 Android/Desktop） | 使用 CLI 桥接。 |
| 硬件加速 | 否 | 是（仅 Android/Desktop） | 使用 CLI 桥接。 |

---

## 从 CLI 迁移到 Kotlin API

迁移前：

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

迁移后：

```kotlin
MediaTranscoder().execute(
    MediaOperation.Remux(
        input = playlistFile,
        output = outputFile,
        bitstreamFilters = mapOf(1 to "aac_adtstoasc"),
        movflags = listOf("faststart"),
        allowedExtensions = "ALL",
        protocolWhitelist = "file,crypto,data",
        ignoreDts = true, // HLS 源通常需要此项
    )
)
```

---

## 平台说明

- **Android**：基于 JavaCPP 的 JNI。需要调用 `FFmpegKit.initialize(context)`。
- **Desktop JVM**：基于 JavaCPP 的 JNI。二进制文件自动从运行时 JAR 中提取。
- **iOS**：Kotlin/Native cinterop，链接静态 FFmpeg 框架。`FFmpegKit.execute(args)` 仅支持 `Remux` 和 `Probe`；转码、滤镜等复杂操作在 iOS 上不可用，因为 App Sandbox 禁止 spawn 子进程。先使用 `./gradlew :mediamp-ffmpeg:ffmpegCreateAppleXcframework` 构建框架。
