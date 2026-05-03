# mediamp-mpv 依赖矩阵

本文档描述当前 `mediamp-mpv` 模块在手工构建 `libmpv` 时实际启用的外部依赖，以及这些依赖在各平台上的导入方式。

说明：

- 本文档描述的是当前仓库里的实际构建配置，不是 mpv 上游的“全功能默认配置”。
- `FFmpeg` 由 `mediamp-ffmpeg` 模块提供，本文会把它列入矩阵，但重点说明除此之外的依赖。
- `Linux` 一列当前表示“脚本已接通的设计状态”，尚未在 Linux 主机上实跑验证。
- `macOS` 当前已补齐 `MacosX64` 和 `MacosArm64` 目标定义，但尚未在 macOS 主机上实跑验证。

相关代码入口：

- [mediamp-mpv/mpv/meson.build](/C:/Users/StageGuard/Desktop/Projects/mediamp/mediamp-mpv/mpv/meson.build)
- [mediamp-mpv/mpv/meson.options](/C:/Users/StageGuard/Desktop/Projects/mediamp/mediamp-mpv/mpv/meson.options)
- [buildSrc/src/main/kotlin/mpv/MpvSupport.kt](/C:/Users/StageGuard/Desktop/Projects/mediamp/buildSrc/src/main/kotlin/mpv/MpvSupport.kt)
- [buildSrc/src/main/kotlin/mpv/MpvTaskTypes.kt](/C:/Users/StageGuard/Desktop/Projects/mediamp/buildSrc/src/main/kotlin/mpv/MpvTaskTypes.kt)
- [buildSrc/src/main/kotlin/mpv/MpvBuildTasks.kt](/C:/Users/StageGuard/Desktop/Projects/mediamp/buildSrc/src/main/kotlin/mpv/MpvBuildTasks.kt)
- [mediamp-mpv/src/cpp](/C:/Users/StageGuard/Desktop/Projects/mediamp/mediamp-mpv/src/cpp)

## 总览

当前 `libmpv` 构建链中显式进入依赖声明的核心第三方库有：

- `FFmpeg`
- `libass`
- `libplacebo`
- `zlib`

其中：

- `FFmpeg` 来自 `mediamp-ffmpeg`，通过 `pkg-config` 提供给 mpv。
- `libass` 用于字幕和 OSD 文本渲染。
- `libplacebo` 用于 GPU 渲染路径。
- `zlib` 在当前配置里被显式启用，同时也被部分 Android fallback 子项目间接使用。

上游支持但当前配置中明确关闭的依赖包括：

- `lua`
- `javascript`
- `sdl2-audio`
- `sdl2-video`
- `dvdnav`
- `libarchive`
- `libbluray`
- `lcms2`
- `vapoursynth`
- `zimg`
- `wayland`
- `vulkan`
- `vaapi`
- `vdpau`
- `xv`

## 第三方依赖矩阵

| 依赖 | 作用 | Windows | Linux | macOS | Android | 最终产物情况 |
|---|---|---|---|---|---|---|
| `FFmpeg` | 解复用、解码、滤镜、缩放、重采样等核心媒体能力 | 来自 `mediamp-ffmpeg` 安装目录，通过 `pkg-config` 导入 | 来自 `mediamp-ffmpeg` 安装目录，通过 `pkg-config` 导入 | 来自 `mediamp-ffmpeg` 安装目录，通过 `pkg-config` 导入 | 来自 `mediamp-ffmpeg` 安装目录，通过 cross file 的 `pkg_config_libdir` 导入 | Win: `av*.dll` / `sw*.dll`；macOS: `libav*.dylib` / `libsw*.dylib`；Android: `libav*.so` / `libsw*.so` |
| `libass` | 字幕、OSD 文本渲染 | MSYS2 UCRT64 包，通过 `pkg-config` 导入 | 预期由 Linux 系统开发包提供，通过 `pkg-config` 导入 | 预期由 macOS 系统包管理器提供，通过 `pkg-config` 导入 | Git wrap fallback 交叉编译 | Win: `libass-9.dll`；macOS: `libass*.dylib`；Android: `libass.so` |
| `libplacebo` | GPU 渲染后端 | MSYS2 UCRT64 包，通过 `pkg-config` 导入 | 预期由 Linux 系统开发包提供，通过 `pkg-config` 导入 | 预期由 macOS 系统包管理器提供，通过 `pkg-config` 导入 | Git wrap fallback 交叉编译 | Win: `libplacebo-351.dll`；macOS: `libplacebo*.dylib`；Android: 当前安装为 `libplacebo.a` |
| `zlib` | 压缩基础库；同时被部分子项目传递使用 | MSYS2 / 系统路径传递提供 | 预期由 Linux 系统包提供 | 预期由 macOS 系统 / 包管理器提供 | WrapDB fallback | Win: `zlib1.dll`；macOS: `libz*.dylib`；Android: `libz.so` |
| `freetype2` | `libass` 字体栅格化 | 作为 `libass` 传递依赖，经 MSYS2 提供 | 预期由 Linux 系统包提供 | 预期由 macOS 系统包管理器提供 | WrapDB fallback | Win: `libfreetype-6.dll`；macOS: `libfreetype*.dylib`；Android: `libfreetype.so` |
| `fribidi` | `libass` 双向文字处理 | 作为 `libass` 传递依赖，经 MSYS2 提供 | 预期由 Linux 系统包提供 | 预期由 macOS 系统包管理器提供 | WrapDB fallback | Win: `libfribidi-0.dll`；macOS: `libfribidi*.dylib`；Android: `libfribidi.so` |
| `harfbuzz` | `libass` 复杂文本 shaping | 作为 `libass` 传递依赖，经 MSYS2 提供 | 预期由 Linux 系统包提供 | 预期由 macOS 系统包管理器提供 | WrapDB fallback | Win: `libharfbuzz-0.dll`；macOS: `libharfbuzz*.dylib`；Android: `libharfbuzz*.so` |
| `libpng` | `freetype2` 传递依赖 | 作为传递依赖由 MSYS2 提供 | 预期由 Linux 系统包提供 | 预期由 macOS 系统包管理器提供 | WrapDB fallback | Win: `libpng16-16.dll`；macOS: `libpng*.dylib`；Android: `libpng16.so` |
| `shaderc` | Windows GPU 路径着色器编译 | MSYS2 UCRT64 包，通过 `pkg-config` 导入 | 当前禁用 | 当前禁用 | 当前禁用 | Win: `libshaderc_shared.dll` |
| `spirv-cross` | Windows GPU 路径 SPIR-V 转换 | MSYS2 UCRT64 包，通过 `pkg-config` 导入 | 当前禁用 | 当前禁用 | 当前禁用 | Win: `libspirv-cross-c-shared.dll` |
| `expat` | Android fallback 依赖集合中的基础库 | 未显式启用 | 未显式启用 | 未显式启用 | WrapDB fallback 依赖集合 | 当前 Android 成功产物中未单独输出 `libexpat.so` |

## 平台系统依赖矩阵

这些依赖不是单独 vendored 的第三方包，但它们是 `libmpv` 在不同平台上依赖的系统 API 或 SDK 组件。

| 依赖 | Windows | Linux | macOS | Android | 导入方式 |
|---|---|---|---|---|---|
| OpenGL | `gl-win32` 启用 | `gl-x11` 启用 | `gl-cocoa` 启用 | `gl` + `egl-android` 启用 | 系统 SDK / NDK sysroot |
| Cocoa | 禁用 | 禁用 | 启用 | 禁用 | Apple frameworks |
| CoreAudio | 禁用 | 禁用 | 启用 | 禁用 | Apple frameworks |
| D3D11 / Direct3D | 启用 | 禁用 | 禁用 | 禁用 | Windows SDK |
| WASAPI | 启用 | 禁用 | 禁用 | 禁用 | Windows SDK |
| X11 / EGL-X11 | 禁用 | 启用 | 禁用 | 禁用 | Linux 系统开发包 |
| AudioTrack | 禁用 | 禁用 | 禁用 | 启用 | Android NDK / platform APIs |
| OpenSL ES | 禁用 | 禁用 | 禁用 | 启用 | Android NDK / platform APIs |
| Android Media NDK | 禁用 | 禁用 | 禁用 | 启用 | Android NDK |
| `pthread` / `libdl` / `libm` | 平台内部处理 | 预期系统提供 | 平台内部处理 | NDK sysroot 提供 | 编译器 / 系统链接器 |
| `libc++_shared.so` | 不需要单独拷贝 | 由系统 libc++/libstdc++ 处理 | 由系统 libc++ 处理 | 需要单独拷贝 | Android NDK toolchain 提供 |

## 各平台依赖导入方式

### Windows

依赖来源分两类：

1. `FFmpeg`
   - 来自 `mediamp-ffmpeg/build/ffmpeg/WindowsX64/install`
   - `mpv` 通过该目录下的 `lib/pkgconfig` 导入

2. 其他三方库
   - 主要由 MSYS2 UCRT64 包管理提供
   - 当前明确安装的包包括：
     - `mingw-w64-ucrt-x86_64-libass`
     - `mingw-w64-ucrt-x86_64-libplacebo`
     - `mingw-w64-ucrt-x86_64-shaderc`
     - `mingw-w64-ucrt-x86_64-spirv-cross`
     - 以及 `gcc/meson/ninja/pkgconf/python` 等构建工具

导入细节：

- `PKG_CONFIG_PATH` 会优先放入 FFmpeg 的 `pkgconfig` 目录，再追加：
  - `/ucrt64/lib/pkgconfig`
  - `/ucrt64/share/pkgconfig`

产物组装方式：

- 先复制 `mpv install prefix`
- 再复制 FFmpeg 的 `bin`
- 最后通过 `objdump` 递归分析 DLL 依赖，从 `C:\msys64\ucrt64\bin` 继续补齐运行时 DLL

### Linux

依赖来源设计如下：

1. `FFmpeg`
   - 来自 `mediamp-ffmpeg/build/ffmpeg/LinuxX64/install`
   - 通过 `pkg-config` 导入

2. 其他三方库
   - 当前设计为依赖 Linux 主机的系统开发包
   - 主要包括：
     - `libass`
     - `libplacebo`
     - `X11`
     - `EGL`
     - `OpenGL`

当前边界：

- Linux 构建逻辑已接通，但尚未在 Linux 主机上实跑验证。
- 目前 Linux 组装阶段只会复制：
  - `mpv install prefix`
  - FFmpeg 的 `lib`
- 不会额外把系统 `libX11/libEGL/libGL/libass/libplacebo` 一起 vendoring 到输出目录。

### Android

依赖来源分三类：

1. `FFmpeg`
   - 来自 `mediamp-ffmpeg/build/ffmpeg/Android<Abi>/install`
   - 通过 Meson cross file 的 `pkg_config_libdir` 指向 ABI 对应的 `pkgconfig` 目录

2. `libass` / `libplacebo`
   - 不使用宿主机库
   - 改为 Meson subproject fallback
   - `libass` 使用 git wrap
   - `libplacebo` 使用 git wrap

3. `libass/libplacebo` 的主要传递依赖
   - 通过 WrapDB fallback 安装和交叉编译：
     - `expat`
     - `freetype2`
     - `fribidi`
     - `harfbuzz`
     - `libpng`
     - `zlib`

额外约束：

- Android cross build 会显式清空宿主机的：
  - `PKG_CONFIG_PATH`
  - `PKG_CONFIG_SYSTEM_INCLUDE_PATH`
  - `PKG_CONFIG_SYSTEM_LIBRARY_PATH`
- 目的是避免把 Windows MSYS2 的头文件和库误串入 Android 编译。

平台 API 来源：

- `egl-android`
- `audiotrack`
- `opensles`
- `android-media-ndk`
- `libc++_shared.so`

它们来自 Android NDK / sysroot，而不是单独第三方包。

产物组装方式：

- 复制 `mpv install prefix`
- 复制 FFmpeg `.so`
- 复制 `libc++_shared.so`

### macOS

依赖来源设计如下：

1. `FFmpeg`
   - 来自 `mediamp-ffmpeg/build/ffmpeg/Macos*/install`
   - 通过 `pkg-config` 导入

2. 其他三方库
   - 当前设计为依赖 macOS 主机上的系统包管理器
   - 预期主要通过 Homebrew/MacPorts 提供：
     - `libass`
     - `libplacebo`
     - `freetype`
     - `fribidi`
     - `harfbuzz`
     - `libpng`

平台特性：

- 启用 `cocoa`、`gl-cocoa`、`coreaudio`
- 启用 `swift-build` 和 `macos-cocoa-cb`
- 当前禁用 `vulkan`

产物组装方式：

- 复制 `mpv install prefix`
- 复制 FFmpeg `lib`
- 对输出目录中的 `.dylib` 执行 `install_name_tool` 重写，改为 `@loader_path/...`

## 当前启用的构建特性

### Windows

- 启用：
  - `gl`
  - `gl-win32`
  - `shaderc`
  - `spirv-cross`
  - `d3d-hwaccel`
  - `d3d11`
  - `direct3d`
  - `wasapi`
- 禁用：
  - `x11`
  - `audiotrack`
  - `opensles`
  - `aaudio`

### Linux

- 启用：
  - `gl`
  - `gl-x11`
  - `egl`
  - `egl-x11`
  - `x11`
- 禁用：
  - `d3d11`
  - `direct3d`
  - `wasapi`
  - `audiotrack`
  - `opensles`
  - `aaudio`

### Android

- 启用：
  - `gl`
  - `egl-android`
  - `audiotrack`
  - `opensles`
  - `android-media-ndk`
- 禁用：
  - `egl`
  - `egl-x11`
  - `x11`
  - `d3d11`
  - `direct3d`
  - `wasapi`
  - `aaudio`
- `vulkan`

### macOS

- 启用：
  - `gl`
  - `cocoa`
  - `gl-cocoa`
  - `coreaudio`
  - `videotoolbox-gl`
  - `swift-build`
  - `macos-cocoa-cb`
- 禁用：
  - `x11`
  - `egl`
  - `d3d11`
  - `direct3d`
  - `wasapi`
  - `audiotrack`
  - `opensles`
  - `aaudio`
  - `vulkan`

Android 子项目额外选项：

- `--force-fallback-for=libass,libplacebo,expat,freetype2,fribidi,harfbuzz,libpng,zlib`
- `-Dlibass:require-system-font-provider=false`
- `-Dlibplacebo:vulkan=disabled`
- `-Dlibplacebo:lcms=disabled`
- `-Dlibplacebo:demos=false`

## 当前边界

- Windows `libmpv` 构建已实跑验证通过。
- Android `arm64-v8a` 构建已实跑验证通过。
- macOS `MacosX64` / `MacosArm64` 目标定义和打包逻辑已补齐，但尚未在 macOS 主机上实跑验证。
- Linux 构建逻辑已接通，但尚未在 Linux 主机上实跑产出。
- 当前仓库仍保留旧 `libmpv/` prebuilt 目录的删除状态；本文档描述的是新的手工构建链路，不再以旧 prebuilt 目录为准。
