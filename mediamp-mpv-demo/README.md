# mediamp-mpv-demo — mpv 硬解 + Compose overlay 原型 (macOS)

验证在 macOS 上用 libmpv 硬件解码 (VideoToolbox) 播放视频, 并把画面作为 Compose Desktop
场景图的一部分渲染, 从而让任意 Compose 组件直接覆盖在视频上 (无 airspace 问题)。

## 运行

```bash
brew install mpv   # 需要 libmpv (本原型在 mpv 0.41.0 / libmpv 2.5 上验证)
./gradlew :mediamp-mpv-demo:run                      # 播放默认测试视频
./gradlew :mediamp-mpv-demo:run --args="/path/to/video.mkv"
```

默认测试视频路径为 `~/.cache/mediamp-mpv-demo/test-1080p-h264.mp4`, 可用 ffmpeg 生成:

```bash
ffmpeg -f lavfi -i "testsrc2=size=1920x1080:rate=30,format=yuv420p" \
       -f lavfi -i "sine=frequency=440" -t 60 -c:v libx264 -c:a aac -shortest \
       ~/.cache/mediamp-mpv-demo/test-1080p-h264.mp4
```

## 架构

```
┌────────────────────────── JVM 进程 ──────────────────────────┐
│                                                              │
│  libmpv (demux/decode)                                       │
│    hwdec=videotoolbox → CVPixelBuffer (GPU, 零拷贝)           │
│         │                                                    │
│  mpv render API (MPV_RENDER_API_TYPE_OPENGL)                 │
│    离屏 CGL context (4.1 Metal-backed GL)                     │
│    渲染到 FBO, 其 color attachment 是 ──┐                     │
│                                        ▼                     │
│               IOSurface (BGRA, 跨 API 共享显存)                │
│                                        │                     │
│  MTLDevice.newTexture(iosurface:) ─────┘                     │
│    (device 指针 = Skiko MetalRedrawer 的同一个 MTLDevice)      │
│         │                                                    │
│  Skia:  BackendRenderTarget.makeMetal(w, h, mtlTexPtr)       │
│         Surface.makeFromBackendRenderTarget(directContext)   │
│         │                                                    │
│  Compose Canvas { surface.draw(nativeCanvas) }               │
│    → 视频只是场景图中的一次 draw, 上面可以叠任意 Compose 组件    │
└──────────────────────────────────────────────────────────────┘
```

- **解码**: VideoToolbox 硬解, 帧留在 GPU (`VO: [libmpv] WxH videotoolbox[nv12]`)。
  mpv 内部的 videotoolbox GL interop 本身就走 IOSurface, 所以解码→渲染全程零拷贝。
- **GL↔Metal 桥**: IOSurface 是 macOS 上跨图形 API 共享显存的标准做法
  (Chromium/Firefox 同款)。macOS 的 OpenGL 已 deprecated 但在 Apple Silicon 上仍由
  Metal 实现支撑 (`GL_VERSION: 4.1 Metal`), 稳定可用。
- **帧驱动**: mpv 的 render update callback → JNI → bump Compose `mutableLongState`
  → Canvas 重绘时调用 `mpv_render_context_render()`。
- **反射点** (Skiko internal API, 版本升级时需要关注):
  - `SkiaLayer.getRedrawer$skiko()` → `MetalRedrawer`
  - `MetalRedrawer.adapter` (`MetalAdapter.ptr` = MTLDevice 指针)
  - `MetalRedrawer.contextHandler` → `ContextHandler.context` (Skia `DirectContext`)

## 实测结果 (M2 Max, macOS 26 / Darwin 25.2, CMP 1.10.1 / Skiko 0.9.37.4)

与现有 VLC 栈 (`:mediamp-mpv-demo:runVlc`, vlcj CallbackVideoSurface) 的对比。两边使用
**完全相同的视频文件、窗口尺寸 (1280×800@2x) 和 Compose overlay** (无限动画强制 ~60fps 重绘)。
CPU 为进程级 (top, 单核百分比, 10 次采样平均); GPU 为系统级 Device Utilization
(ioreg, 空载基线 ~33–36%, 无法按进程归因, 仅供参考)。

| 场景 (同一文件) | mpv 原型 CPU | VLC 基线 CPU | 备注 |
|---|---|---|---|
| 1080p30 H.264 | 8.1% | 24.3% | |
| 1080p60 H.264 | 6.1% | 30.0% | VLC 日志出现 `pic_holder_wait timed out` |
| 4K60 HEVC | 12.1% | 22.4% | |
| **1080p60 HEVC 10-bit 44Mbps (模拟番剧)** | **7.7%** | **40.3% + 持续丢帧** | VLC: `picture is too late to be displayed` ×8+/25s |

GPU 占用两者差异在系统噪声内 (基线 ~36%, 播放中 37–50%), 都远未打满。

**重要发现**: 在 macOS 上 mediamp-vlc 的 callback 管线里 VLC 其实**也在用 VideoToolbox
硬解** (`Using Video Toolbox to decode 'hevc'`)。真正的性能差距来自渲染管线:
VLC 解码后必须 GPU→CPU 拷回 + 色度转换 (cvpx 420v/p010 → RV32) + 每帧 CPU 上传给
Skia bitmap; 而 mpv 方案全程留在 GPU。这解释了为什么 VLC 即使硬解也会在高码率
10-bit 片源上丢帧 ("卡"), 且 CPU 是 mpv 的 3–5 倍。

Compose overlay (Material3 按钮/Slider/无限动画/半透明渐变) 在两个后端上都正常,
但只有 mpv 方案是零拷贝合成。

**无需升级 CMP**: 仓库现有的 CMP 1.10.1 (Skiko 0.9.37.4) 已提供全部所需 API
(`BackendRenderTarget.makeMetal` + `Surface.makeFromBackendRenderTarget`)。
Skiko 没有 `BackendTexture.makeMetal` (只有 GL 版), 所以采用 wrap-as-render-target
而非 adopt-as-image 的方式, 两者渲染结果等价。

## 与现有分支的关系

- `sg/mpv-rendering` (最新, 2026-04): 同样的 "mpv 渲染进 Skia 纹理" 思路, 但通过
  **共享 Skia 的 OpenGL context** 实现, 依赖 `WindowsOpenGLRedrawer`, 仅支持 Windows。
  macOS 上 Skiko 只有 Metal/软件后端, 无法照搬; 本原型的 IOSurface 桥即是 macOS 版答案,
  且因为 GL 与 Metal 状态隔离, 不需要该分支的 `resetGL` 状态修复。
- `him188/mpv-rendering-offscreen` (2025-06): 离屏 GL + 读回, 已被上述方案取代。
- `mediamp-mpv` 模块 (main): 已有完整的 mpv JNI 封装 (事件/属性/命令) 但被从
  settings.gradle.kts 注释掉; 本原型的渲染桥可作为其 macOS `MpvMediampPlayerSurface`
  的实现蓝本。

## 已知限制 / 生产化 TODO

- 单 IOSurface, 无双/三缓冲: GL 写与 Metal 读之间仅靠 `glFlush()` 同步, 理论上可能
  撕裂 (实测未观察到)。生产实现建议 2–3 个 IOSurface 轮转 + `MPV_RENDER_PARAM_ADVANCED_CONTROL`。
- 窗口 resize 时整条 IOSurface/FBO/MTLTexture 链重建 (帧级别开销, 可接受, 但可加防抖)。
- Intel Mac 未测试 (MTLStorageModeShared 在独显机器上需要改 Managed/Private)。
- Skiko 内部 API 反射在 Skiko 升级时可能失效, 建议向 JetBrains 提议公开
  "external texture into Compose scene" 的官方 API (相关 issue: SKIKO-….)。
- libmpv 目前来自 Homebrew; 发布需要打包自带 (仓库已有 `mpvAssembleMacosArm64`
  replicable build 基建, 见 `buildSrc/src/main/kotlin/mpv`)。
