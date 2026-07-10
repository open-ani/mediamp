# mediamp-mpv Windows D3D11 渲染方案

基于 mpv PR [#17764](https://github.com/mpv-player/mpv/pull/17764)(libmpv D3D11 render API),
为 Windows 实现与 macOS Metal 路径同构的 D3D11 渲染,并删除现有 legacy OpenGL 实现。

本文档是实施前的调研结论与分步计划。调研时间:2026-07。

> **状态:已实施(2026-07-08)。** Phase 0–3 全部完成并在 Windows 上冒烟验证通过
> (`:mediamp-mpv-demo:runD3D11` 播放 h264 视频, Compose overlay 混合正常, native
> 环 PNG 读回像素正确)。与计划的偏差:
> - PR 以 `mediamp-mpv/render_d3d11.patch` 落地(构建链已有 patch 机制), 未建 fork;
> - 帧同步用 `D3D11_QUERY_EVENT` 轮询(等价 glFinish), 未用 shared fence(留作 Phase 4);
> - alpha 无需处理:`RGB_888x` 在 Skia D3D12 后端不可 wrap(非 renderable, 返回 null),
>   实际用 `RGBA_8888`, mpv d3d11 渲染器对不透明视频输出 alpha=1(像素验证);
> - 消费端状态机与 Compose surface 已抽为两平台共享(`internal/MpvSurfaceRing.kt`,
>   统一的 `MpvMediampPlayerSurfaceRing` composable), macOS 行为不变;
> - 附带修复:`mpvRuntimeAllElements` 聚合 variant 缺 capability, 导致项目内
>   `implementation(projects.mediampMpv)` 被解析到该 variant(buildSrc 已修)。
> 当前工作流见 [dependency.md](dependency.md) "Windows 渲染与开发工作流"。

---

## 1. 结论(TL;DR)

**可行,且是当前 Windows 路径的正确替代。** 三个前提全部成立:

1. **mpv 侧**:PR #17764 新增 `MPV_RENDER_API_TYPE_D3D11`,客户端提供 `ID3D11Device`,
   每帧往客户端的 `ID3D11Texture2D` 里渲染。PR 尚未合并(open,mergeable clean,目标
   0.42.0),但我们的 libmpv 本来就是在仓库内从源码 meson 构建(子模块正好是 **v0.41.0**),
   PR 总共只改 9 个文件 ~240 行,以 fork + cherry-pick 方式打进我们的构建完全现实。
   它依赖的 `ra_d3d11` + spirv(shaderc/spirv-cross)在我们的 Windows meson 配置里**已经启用**。

2. **Skia/Skiko 侧**:Compose Desktop 在 Windows 默认走 **Direct3D 12**(`Direct3DRedrawer`)。
   我们用的 Skiko 0.9.37.4 **公开**了 `BackendRenderTarget.makeDirect3D(w, h, ID3D12Resource*, dxgiFormat, sampleCnt, levelCnt)`
   和 `Surface.makeFromBackendRenderTarget(...)` —— 与 macOS 路径用的
   `BackendRenderTarget.makeMetal` 完全同构,消费端 Kotlin 代码可以照搬 macOS 的
   "wrap → blit → snapshot" 模式。

3. **D3D11 → D3D12 互操作**:mpv 渲染在我们自建的 D3D11 device 上,Skia 在 Skiko 的
   D3D12 device 上,标准解法是 **NT shared handle 共享纹理 + shared fence 同步**
   (微软官方文档明确此为 D3D11↔D3D12 互操作机制)。结构上等价于 macOS 的
   "CGL 渲染进 IOSurface、Metal 采样同一 IOSurface"。

**附带收益**(相对现有 GL 路径):

- 现有 Windows GL 路径要求宿主 App 强制 `SKIKO_RENDER_API=OPENGL`(反射的是
  `WindowsOpenGLRedrawer`),而 Compose 默认是 D3D12,**Windows ARM64 上 Skiko 干脆禁用
  OpenGL** —— 新路径直接工作在 Compose 默认后端上。
- `hwdec=d3d11va` 与 mpv 的 d3d11 ra 同设备,解码帧全程留在 GPU(现有 GL 路径 hwdec 帧
  要经 GL 互操作或拷贝)。
- 摆脱 `glFinish()` 阻塞 UI 线程的同步渲染:采用 macOS 的原生渲染线程 + 三重缓冲环模型,
  UI 绘制路径零渲染、零阻塞。
- D3D11 支持 WARP 软渲染 fallback,headless CI(GitHub Windows runner)上比 GL 可靠得多。

**主要风险**(详见 §6):PR 未合并、API 可能微调;Skiko 原生 `DirectXDevice` 结构体字段
偏移非 ABI 保证,需要运行时 `QueryInterface` 验证;mpv 输出 alpha 未定义的问题需要 D3D11
侧对策(macOS 已踩过同一坑)。

---

## 2. 调研依据

### 2.1 mpv PR #17764 的确切 API

- 作者 kasper93,2026-04-17 创建,head = `kasper93/mpv@render_d3d11`
  (sha `87316d318beb8d7a25e5e6fbc4fae210a9250ac0`),base = master,状态 open / mergeable clean,
  里程碑 0.42.0。`MPV_CLIENT_API_VERSION` bump 到 2.6。
- 改动文件:`include/mpv/render_d3d11.h`(新增,104 行)、`include/mpv/render.h`(+14)、
  `include/mpv/client.h`(版本号)、`video/out/d3d11/libmpv_d3d11.c`(新增,109 行)、
  `video/out/gpu/libmpv_gpu.c/.h`(注册 backend,+4)、`meson.build`(+4)、文档 2 个。
- API:
  ```c
  #define MPV_RENDER_API_TYPE_D3D11 "d3d11"
  MPV_RENDER_PARAM_D3D11_INIT_PARAMS = 21   // mpv_render_context_create() 时传
  MPV_RENDER_PARAM_D3D11_FBO         = 22   // 每次 mpv_render_context_render() 传

  typedef struct mpv_d3d11_init_params {
      void *device;          // ID3D11Device*;libmpv AddRef,须存活到 context 销毁
  } mpv_d3d11_init_params;

  typedef struct mpv_d3d11_fbo {
      void *tex;             // ID3D11Texture2D*;调用方持有所有权,libmpv 不加引用
      int w, h;              // 必须与纹理实际尺寸一致
  } mpv_d3d11_fbo;
  ```
- 约束:device **不能**带 `D3D11_CREATE_DEVICE_SINGLETHREADED`(任意线程可调 render 函数);
  目标纹理必须 `D3D11_BIND_RENDER_TARGET` + `D3D11_USAGE_DEFAULT`,不能是多重采样/数组/mipmap。
- 实现细节(`libmpv_d3d11.c`):`init` 里 `spirv_compiler_init` + `ra_d3d11_create(device)`;
  `wrap_fbo` 对同一 `ID3D11Texture2D` 缓存 `ra_tex`(换纹理时 free + 重新 wrap —— 我们的
  三缓冲环会每帧轮换纹理,每帧一次 `ra_d3d11_wrap_tex`,该操作只是创建 RTV,开销可接受);
  `done_frame` 调 `ra_d3d11_flush`。
- 评审中已知问题:resize 时旧纹理引用残留导致 `ResizeBuffers` 报错(针对 swapchain 场景);
  我们不用 swapchain,ring 换代时纹理指针必然变化、旧 `ra_tex` 会被 wrap 缓存逻辑释放,
  受影响面小,但换代后**首帧前**建议主动传一次新 FBO 而不是复用(自然满足)。

### 2.2 我们的 mpv 构建现状

- `mediamp-mpv/mpv` 子模块 = 上游 `v0.41.0` release commit(`41f6a645`)。
- Windows meson 配置已启用:`gl`、`gl-win32`、`shaderc`、`spirv-cross`、`d3d-hwaccel`、
  `d3d11`、`direct3d`、`wasapi`(见 [dependency.md](dependency.md))—— PR 需要的
  `ra_d3d11` 与 spirv 编译链**零额外依赖**。
- JNI wrapper 由 `buildSrc`(`MpvJniBuildTask`)直接调 MSYS2 UCRT64 g++ 编译,Windows
  链接参数**已包含** `-ld3d11 -ld3d12 -ldxgi -ldxguid`(`MpvBuildTasks.kt:274`)。

### 2.3 Skiko(0.9.37.4,随 Compose 1.10.1)的 D3D 能力

已对照该版本的 sources jar 逐一确认:

| 能力 | 结论 |
|---|---|
| Windows 默认渲染后端 | **DIRECT3D(D3D12)**,`Direct3DRedrawer`;fallback 队列 ANGLE→D3D→GL→软渲染;**ARM64 禁 GL** |
| `DirectContext` 获取 | 与 macOS 相同的反射配方:redrawer 私有字段 `contextHandler` → `ContextHandler.context` |
| `Direct3DRedrawer.device` 字段 | `Long`,指向 Skiko 原生 C++ `DirectXDevice` 结构(**不是** `ID3D12Device*` 本身),内含 `hWnd`、`GrD3DBackendContext{fAdapter,fDevice,fQueue,...}`、`device`、`swapChain`、`queue`、`fence` 等 COM 指针 |
| `BackendRenderTarget.makeDirect3D(w, h, texturePtr, format, sampleCnt, levelCnt)` | **有,public**;`texturePtr` = 裸 `ID3D12Resource*`,`format` = DXGI_FORMAT 整数值 |
| `Surface.makeFromBackendRenderTarget` / `surface.draw` / `makeImageSnapshot` | 有,public(macOS 路径正在用) |
| `BackendTexture` 的 D3D 工厂 / `Image.adoptTextureFrom`(D3D) | **无** —— 现有 GL 路径的 adoptTexture 模式在 D3D 下不可用,必须走 BRT + blit 模式(macOS 已验证该模式) |
| Skiko 每帧 flush 行为 | `Direct3DContextHandler` 每帧 `flush + submit(GrSyncCpu::kYes)`(CPU 同步),降低外部纹理生命周期风险,但属实现细节,不可静默依赖 |
| 备选 | Skiko 另有 `@ExperimentalSkikoApi DirectXOffscreenContext`(自建离屏 D3D12 device),证明 wrap 路径可用,但与屏上 redrawer 不同设备,不直接可用 |

**关键缺口**:Skiko 没有 Kotlin 可见的 API 返回 redrawer 的 `ID3D12Device*`/`ID3D12CommandQueue*`。
必须反射拿到 `device: Long`(`DirectXDevice*`),在**我们自己的 JNI** 里解引用。结构体字段
偏移(x64 推断:`hWnd@0, fAdapter@8, fDevice@16, fQueue@24, ...`)不是 ABI 保证 —— 原生侧
必须对候选指针槽逐个 `QueryInterface(IID_ID3D12Device / IID_ID3D12CommandQueue)` 验证后才使用
(拿不到就明确报错降级)。这与 `SkiaMetalInterop` 反射 Skiko 内部的既有风险同类,Skiko
升级时需回归(macOS 路径已有"verified against Skiko 0.9.37"的先例注释)。

### 2.4 D3D11 → D3D12 共享的标准配方(微软文档确认)

1. **纹理共享**:D3D11 侧创建纹理带
   `D3D11_RESOURCE_MISC_SHARED | D3D11_RESOURCE_MISC_SHARED_NTHANDLE`,
   `IDXGIResource1::CreateSharedHandle` 取 NT 句柄,在 Skia 的 D3D12 device 上
   `ID3D12Device::OpenSharedHandle` 得 `ID3D12Resource*`。格式用
   `DXGI_FORMAT_B8G8R8A8_UNORM` 或 `R8G8B8A8_UNORM`(Skiko 自己的 swapchain 用 R8G8B8A8)。
2. **同步**:D3D12 资源没有 keyed mutex,**用 shared fence**:
   `ID3D11Device5::CreateFence(D3D11_FENCE_FLAG_SHARED)` + `ID3D11DeviceContext4::Signal`;
   消费端 `ID3D12Device::OpenSharedHandle` 打开同一 fence。
   首版采用更简单的等价物:渲染线程 Signal 后 **CPU 等待 fence 完成再 publish**
   (`SetEventOnCompletion`)—— 语义上等同 macOS 路径的 `glFinish()`,且等待发生在
   渲染线程,不阻塞 UI。GPU 侧 `ID3D12CommandQueue::Wait` 留作后续优化。

### 2.5 先例

- 上游一直没有 D3D11 render API(issue #5979 悬置多年),Windows 嵌入方案历来是
  GL-over-ANGLE(Flutter media_kit 即此路线)。PR #17764 是第一次原生支持。
- Compose/Skiko 生态**没有**已知项目把外部 D3D 纹理导入 D3D12 后端 —— 我们会是第一个,
  但所用 API(`makeDirect3D` + `makeFromBackendRenderTarget`)正是 Skiko 自己包装
  swapchain buffer 的方式,不是野路子。

---

## 3. 目标架构:macOS ↔ Windows 对照

整体照搬 macOS 的线程模型与状态机(`render_macos.mm` 的设计原封不动),只替换图形 API 层:

| 构件 | macOS(现有) | Windows D3D11(目标) |
|---|---|---|
| mpv render API | `MPV_RENDER_API_TYPE_OPENGL` over 离屏 CGL | `MPV_RENDER_API_TYPE_D3D11`(自建 `ID3D11Device`) |
| 共享缓冲 | IOSurface ×3(GL FBO ↔ MTLTexture 双包装) | D3D11 shared texture ×3(NT handle ↔ `ID3D12Resource`) |
| 渲染线程 | 有;CGL context 常驻线程 | 有;D3D11 无线程亲和,结构照抄 |
| 帧完成同步 | `glFinish()` 后 publish | fence Signal + CPU wait 后 publish |
| 帧状态发布 | packed `frame_state_`(gen/index/w/h/serial 原子量) | 同一格式,原样复用 |
| resize 换代 | retire 旧环 → 消费端 ack → 释放;150ms 防抖 | 相同协议 |
| 无 surface 时 | `MPV_RENDER_PARAM_SKIP_RENDERING` drain | 相同 |
| 消费端 wrap | `BackendRenderTarget.makeMetal(w,h,mtlTexPtr)` | `BackendRenderTarget.makeDirect3D(w,h,d3d12ResPtr,DXGI_FORMAT,1,1)` |
| 绘制 | wrap → `notifyContentWillChange` → blit surface → snapshot → Canvas.drawImage(letterbox) | 相同(代码可共享) |
| Skiko 设备互操作 | `SkiaMetalInterop`(反射 MetalRedrawer.adapter) | 新增 `SkiaDirectXInterop`(反射 Direct3DRedrawer.device + JNI 内 QI 验证) |
| 截图 | IOSurface lock + CGImage/ImageIO → PNG | staging texture `CopyResource` + `Map` + WIC → PNG |
| hwdec | videotoolbox(GL interop) | d3d11va(与 ra 同设备,零拷贝) |
| mpv 选项 | `vo=libmpv`,`ao=coreaudio` | `vo=libmpv`,`ao=wasapi`(删除 `gpu-context=win,opengl`、`opengl-es`、`fbo-format`) |

数据流:

```
mpv 渲染线程 (我们的 native thread)
  mpv_render_context_render(D3D11_FBO = ring[next].d3d11_tex)
  → alpha 修正(如需,见 §6.3)
  → ID3D11DeviceContext4::Signal(fence) → CPU wait
  → latest_index_ = next; publish frame_state_; notify JVM
                                   │
Compose UI 线程(只读,零渲染)      ▼
  frameTick → Canvas 重组
  → nGetFrameStateD3D11 解包 gen/index/w/h
  → (换代时) rewrap: nGetBufferTextureD3D11(i) = ID3D12Resource*
       → BackendRenderTarget.makeDirect3D → Surface.makeFromBackendRenderTarget
  → source.notifyContentWillChange → blit.draw → makeImageSnapshot
  → drawImage(letterbox)
```

---

## 4. 分步实施计划

### Phase 0 — mpv 源码打入 PR(前置,可独立验证)

**Step 0.1** 建 fork(如 `open-ani/mpv`),分支 `mediamp/v0.41.0-d3d11`:基于上游 `v0.41.0`
tag,cherry-pick PR #17764 的 commit(head sha `87316d3`)。PR base 是 post-0.41 的 master,
`libmpv_gpu.h` 接口在 0.41→master 间预计无破坏性变化,若 cherry-pick 冲突需手工回移
(改动面小,9 文件)。`.gitmodules` 的 `mediamp-mpv/mpv.url` 指向 fork,子模块 bump。

**Step 0.2** 本地验证:`./gradlew :mediamp-mpv:mpvBuildWindowsX64`,确认
`video/out/d3d11/libmpv_d3d11.c` 编译、install prefix 里出现 `include/mpv/render_d3d11.h`。
meson 无需新开关(`d3d11` feature 已启用)。

**Step 0.3** 写一个最小 C 冒烟(可直接做成 Phase 3 的测试雏形):`D3D11CreateDevice` →
`mpv_render_context_create(MPV_RENDER_API_TYPE_D3D11)` → loadfile → render 到一张普通
`ID3D11Texture2D` → staging 读回验证非全黑。这一步把"PR 本身能不能工作"与后面所有
Skia 集成解耦。

### Phase 1 — Native 层(C++/JNI)

**Step 1.1** `src/cpp/include/mpv_handle_t.h`:
- 删除 `_WIN32` 的 GL 段:方法 `create_render_context(HDC,HGLRC)`、`create_texture`、
  `release_texture`、`render_frame`(60–69 行),成员 `HGLRC context_`、`HDC device_`、
  `GLuint fbo_/texture_`、`width_/height_`、`texture_lock`(116–124 行),
  以及 `<gl/GL.h>` include。
- 新增 `_WIN32` D3D11 段,**逐成员镜像 `__APPLE__` 段**(126–179 行):
  `render_context_`、`kBufferCount=3`、`struct d3d11_buffer { ID3D11Texture2D* d3d11_tex;
  HANDLE shared_handle; ID3D12Resource* d3d12_res; }`、`buffers_[3]` / `retired_buffers_[3]`、
  `frame_state_` 原子量、`config_pending_/render_pending_/retire_ack_pending_/render_quit_`、
  `render_mutex_/render_cv_/render_thread_`,加上 D3D 特有的:`ID3D11Device* d3d_device_`、
  `ID3D11DeviceContext* d3d_ctx_`(+`ID3D11DeviceContext4`)、`ID3D11Fence* fence_` +
  `uint64_t fence_value_` + `HANDLE fence_event_`、消费端 D3D12 侧 `ID3D12Device* skia_device_`。
  方法签名镜像 macOS:`create_render_context()`(无参)、`set_surface_config(int,int,int64_t)`、
  `get_frame_state()`、`get_buffer_texture(int)`、`ack_retired_buffers()`、`has_d3d11_surface()`、
  `save_surface_png(const char*)`、私有 `render_thread_loop/apply_config_locked/allocate_buffer/
  destroy_buffer_ring/publish_state_locked/render_into/drain_one_frame`。

**Step 1.2** 新文件 `src/cpp/render_d3d11.cpp`(整体 `#ifdef _WIN32` 保护,对应
`render_macos.mm` 的角色):
- `create_render_context()`:`D3D11CreateDevice(nullptr, HARDWARE, BGRA_SUPPORT,
  FL 11_1/11_0, ...)`(**不带** SINGLETHREADED;失败时 `D3D_DRIVER_TYPE_WARP` 兜底,
  供 headless CI);`QueryInterface` 出 `ID3D11Device5`/`ID3D11DeviceContext4`,
  `CreateFence(D3D11_FENCE_FLAG_SHARED)`;`mpv_render_context_create` 传
  `MPV_RENDER_PARAM_API_TYPE=d3d11` + `mpv_d3d11_init_params{device}`;注册
  `on_render_update` 回调;`start_render_thread()`。
  与 macOS 相同:**窗口无关,播放器构造时即可 eager 创建**(`vo=libmpv` 要求 loadfile
  前 render context 已存在,否则 "no audio or video data played")。
- `render_thread_loop()`:从 `render_macos.mm:195-271` 原样移植(wait cv → 处理 ack →
  应用 config → 无 surface 时 `drain_one_frame`(`MPV_RENDER_PARAM_SKIP_RENDERING`)→
  `mpv_render_context_update` → `render_into(next)` → publish → notify)。JVM 预 attach
  逻辑照搬。D3D11 无 per-thread context,少一层 make-current。
- `apply_config_locked()` / `allocate_buffer()`:纹理 desc =
  `{w, h, mips=1, array=1, DXGI_FORMAT_R8G8B8A8_UNORM, sample=1, USAGE_DEFAULT,
  BIND_RENDER_TARGET|BIND_SHADER_RESOURCE, MISC_SHARED|MISC_SHARED_NTHANDLE}`;
  `IDXGIResource1::CreateSharedHandle` → 若有消费端 device(`skia_device_`,来自
  `set_surface_config` 的第三参)则 `OpenSharedHandle` 得 `ID3D12Resource*`;
  `mtl_device_ptr==0`(headless)时不打开 D3D12 端,`get_buffer_texture` 返回 0。
  retire/ack 换代协议与 macOS 一致。
- `set_surface_config(w, h, skiko_device_ptr)`:`skiko_device_ptr` 是 Kotlin 反射来的
  Skiko `DirectXDevice*`。原生侧**指针槽扫描 + QueryInterface 验证**提取
  `ID3D12Device*`(候选偏移 16/48 等,QI 失败继续,全部失败则报错并置 0 —— 消费端
  wrap 会失败,视频黑屏但播放不断,行为等同 macOS interop 失败分支)。
- `render_into(buffer)`:`mpv_render_context_render(MPV_RENDER_PARAM_D3D11_FBO=
  {buffer.d3d11_tex, w, h})` → (alpha 修正,§6.3)→ `d3d_ctx4_->Signal(fence_, ++fence_value_)`
  → `fence_->SetEventOnCompletion` + `WaitForSingleObject`(= `glFinish` 等价,在渲染线程)。
- `save_surface_png(path)`:锁内取 `buffers_[latest_index_].d3d11_tex` →
  `CreateTexture2D(USAGE_STAGING, CPU_ACCESS_READ)` + `CopyResource` + `Map` →
  WIC(`IWICImagingFactory` → PNG encoder)写文件。对应 macOS 的 IOSurface+ImageIO 读回,
  同样绕开 mpv screenshot 命令(桌面构建无 zimg,hwdec 帧转换不可靠)。
- `cleanup_render_resources()`:stop 线程 → 销毁两套 ring(`CloseHandle` shared handle、
  Release 两侧 COM)→ `mpv_render_context_free` → Release device/fence。

**Step 1.3** `src/cpp/mpv_handle_t.cpp`:删除 `#ifdef _WIN32` 的整个 GL 实现段
(719–928 行:`create_render_context(HDC,HGLRC)`、`get_proc_address_mpv`、
`destroy_render_context`、`create_texture`、`release_texture[_impl]`、`render_frame`)
和 1013–1048 行的 GL 版 `cleanup_render_resources`(改由 `render_d3d11.cpp` 提供)。
文件头部 `_WIN32` 的 GL include/前置声明一并清理。

**Step 1.4** `src/cpp/jni.cpp`:
- 删除 GL externals(356–391 行):`nCreateRenderContext`、`nDestroyRenderContext`、
  `nCreateTexture`、`nReleaseTexture`、`nRenderFrameToTexture`。
- 新增 `_WIN32` 段,命名与 macOS 对称(`FN_DESKTOP` = `Java_..._MPVHandleDesktop_`):
  `nCreateRenderContextD3D11(ptr)`、`nDestroyRenderContextD3D11(ptr)`、
  `nSetSurfaceConfigD3D11(ptr, w, h, skikoDevicePtr)`、`nGetFrameStateD3D11(ptr): jlong`、
  `nGetBufferTextureD3D11(ptr, index): jlong`(返回 `ID3D12Resource*`)、
  `nAckRetiredBuffersD3D11(ptr)`、`nHasD3D11Surface(ptr)`、`nSaveSurfacePngD3D11(ptr, path)`。

**Step 1.5** buildSrc:`MpvTaskTypes.kt:347` 的源文件收集已含 `.cpp`,新文件自动纳入
(自身 `#ifdef _WIN32` 保护,其余平台编译为空)。链接参数追加 WIC/COM:
`MpvBuildTasks.kt:274` 在现有 `-lopengl32 -ld3d11 -ld3d12 -ldxgi -ldxguid` 上加
`-lwindowscodecs -lole32`;`-lopengl32` 在 Kotlin 侧 GL 路径删净后移除。

### Phase 2 — Kotlin 层

**Step 2.1** 新增 `desktopMain/kotlin/utils/SkiaDirectXInterop.kt`(镜像
`SkiaMetalInterop.kt` 的结构与注释风格):
- 反射 `SkiaLayer.getRedrawer$skiko`;要求 redrawer 是
  `org.jetbrains.skiko.redrawer.Direct3DRedrawer`,否则抛错(错误信息指明"Windows 需要
  Compose 默认 D3D12 后端,不再支持 SKIKO_RENDER_API=OPENGL");
- `directXDevicePtr: Long` ← 私有字段 `device`;
- `directContext: DirectContext?` ← 私有字段 `contextHandler` →
  `ContextHandler.context`(与 macOS 完全相同的两跳反射);
- 每次现取不缓存(redrawer 可在运行时重建),注明 verified 的 Skiko 版本(0.9.37.4)。

**Step 2.2** `MPVHandle.desktop.kt`:删除 5 个 GL externals(15–27 行)及其 `actual`
包装(79–100 行),新增 Step 1.4 的 8 个 D3D11 externals。

**Step 2.3** `MpvMediampPlayer.desktop.kt`:把现有 macOS 消费端逻辑推广为双平台:
- `init` 的 eager 创建(33–42 行)扩到 Windows:`OS.Windows -> nCreateRenderContextD3D11`。
- 消费端状态机(wrap 缓存、generation 追踪、blit surface、cached frame)与
  `currentFrameImage` / `rewrapBuffers` / `requestSurface` / `refreshDeviceIfChanged` /
  `releaseSurface` 逻辑对两平台**只差三处**:JNI 函数名、wrap 调用
  (`makeMetal(w,h,texPtr)` vs `makeDirect3D(w,h,resPtr,DXGI_FORMAT_R8G8B8A8_UNORM,1,1)`)、
  device 指针语义(MTLDevice* vs Skiko DirectXDevice*)。抽一个私有
  `NativeBufferRing` 策略接口(macos/d3d11 两个实现),消费端状态机写一份 —— 避免
  300 行 near-duplicate。macOS 行为不变是硬性要求(冒烟测试保底)。
- `takeScreenshotImpl` override:Windows 分支走 `nSaveSurfacePngD3D11`,无 surface 时
  与 macOS 相同的"临时 ring(device=0)→ 等首帧 → 存图 → 拆除"流程(headless 时
  D3D11 侧纹理即可读回,无需 D3D12)。
- 删除 GL 遗留:`currentSize`(jvm 71 行)、`backendTexture`/`image`/
  `releaseSkiaTextureAndImage`(desktop 45–57 行)。

**Step 2.4** `MpvMediampPlayer.jvm.kt` 选项(234–244 行)改为:
```
Platform.Windows -> {
    handle.option("ao", "wasapi")
    handle.option("vo", "libmpv")      // render API 自带 d3d11 后端,gpu-context 不适用
}
```
删除 `gpu-context=win,opengl`、`opengl-es=no`、`fbo-format=rgba8`、`dither-depth=no`、
`video-sync=audio`、`video-timing-offset=0`(后两个当年是给同步 GL 路径消抖用的;
渲染线程模型下与 macOS 对齐,先删,冒烟测试如发现掉帧再单独评估)。
`hwdec=auto` 保持 —— mpv 会在我们的 d3d11 ra 上选 d3d11va。
同时删除 GL 专用 API:`createRenderContext(devicePtr, contextPtr)`、`createTexture`、
`releaseTexture`、`renderFrame` 及伴生 externals、`GL_TEXTURE_2D`/`GL_RGBA8` 常量。

**Step 2.5** `compose/MpvMediampPlayerSurface.desktop.kt`:
- 删除 `MpvMediampPlayerSurfaceWindows`(204–283 行)、`utils/OpenGLComponentProvider.kt`
  (整文件)、`compose/FrameInterpolator.kt`(整文件,仅 GL 路径使用)。
- 现有 `MpvMediampPlayerSurfaceMacos`(68–191 行)参数化为通用
  `MpvMediampPlayerSurfaceRing`:注入 interop 提供器(SkiaMetalInterop / SkiaDirectXInterop)
  与 request/refresh/release 调用;150ms resize 防抖、letterbox 绘制、frameTick 订阅
  全部共享。`when (hostOs)` 里 Windows 走同一 composable。

### Phase 3 — 测试与 CI

**Step 3.1** `MpvMediampPlayerSmokeTest.kt`:
- `prepareOrSkip`(58–70 行)从 "Mac only" 放开到 Windows;`devNativeDir` 接受
  `mediampv.dll`。
- `startHeadlessRenderer`(117–126 行)加 Windows 分支:
  `createD3D11RenderContext()` + `nSetSurfaceConfigD3D11(ptr, 640, 360, 0)`
  (device=0 → 纯 D3D11 headless ring;WARP 兜底保证无 GPU runner 可跑)。
- 像素验证(红/蓝双段视频、中心 20×20 取样)零改动复用 —— 截图走
  `nSaveSurfacePngD3D11` 的 staging 读回。
- Windows 本地开发注意:**没有 brew mpv 等价物** —— 官方/shinchiro prebuilt libmpv
  不含未合并的 D3D11 API,dev 流程必须用我们 patched 源码构建的 libmpv
  (`mpvBuildWindowsX64` 产物或 CI runtime jar);如需 `compileJniDevWindows` 快捷任务,
  指向本机已构建的 install prefix。

**Step 3.2** CI(`.github/workflows/src.main.kts`):Windows runtime jar 构建自动
包含 patched mpv(Phase 0 子模块 bump 后无需额外改动);Windows job 的桌面测试开
`-Dmediamp.mpv.test.required=true`,与 macOS 对齐。

**Step 3.3** 文档:更新 [dependency.md](dependency.md)(Windows 渲染路径描述、
`render_d3d11.cpp` 工作流一节,对应现有 "macOS 渲染与开发工作流");记录 fork/patch
的来源与升级策略(PR 合并进 0.42 后,子模块直接升级并删 fork 分支)。

### Phase 4 — 收尾(可选优化,单独 PR)

- fence 从 CPU wait 升级为消费端 `ID3D12CommandQueue::Wait`(Skiko `DirectXDevice.queue`
  槽位同样需 QI 验证),消除渲染线程每帧一次的 CPU 同步。
- `gl-win32` meson feature 评估裁剪(mpv 内部 GL 上下文已不再需要,可减小产物)。
- Windows ARM64 目标(D3D11 路径天然支持,GL 路径做不到)。

---

## 5. 删除清单(legacy OpenGL)

| 位置 | 内容 |
|---|---|
| `src/cpp/mpv_handle_t.cpp:719-928, 1013-1048` | GL render context / texture / FBO / render_frame / cleanup |
| `src/cpp/include/mpv_handle_t.h` `_WIN32` 段 | GL 方法与成员、`<gl/GL.h>` |
| `src/cpp/jni.cpp:356-391` | 5 个 GL JNI 导出 |
| `src/desktopMain/.../utils/OpenGLComponentProvider.kt` | 整文件 |
| `src/desktopMain/.../compose/FrameInterpolator.kt` | 整文件 |
| `src/desktopMain/.../compose/MpvMediampPlayerSurface.desktop.kt:200-283` | GL 版 Windows composable |
| `src/desktopMain/.../MPVHandle.desktop.kt` | GL externals + actual 包装 |
| `src/desktopMain/.../MpvMediampPlayer.desktop.kt:45-57` | `backendTexture`/`image`/`releaseSkiaTextureAndImage` |
| `src/jvmMain/.../MpvMediampPlayer.jvm.kt` | `currentSize`、createRenderContext/createTexture/renderFrame/releaseTexture、GL 常量、`gpu-context=win,opengl` 等选项 |
| `MpvBuildTasks.kt:274` | `-lopengl32` |

`mediamp-mpv-demo` 里的 GL/VLC 原型不在本次范围(独立 demo 模块,另行清理)。

## 6. 风险与缓解

1. **PR 未合并,API 可能变**(param 枚举值 21/22、结构布局、行为)。
   缓解:pin 到明确 sha 的 fork 分支;`dependency.md` 记录来源;订阅 PR,合并进 0.42
   后升级子模块、删 patch。枚举值由头文件带入,我们不硬编码数值,重编译即适配。
2. **Skiko `DirectXDevice` 字段偏移是推断**(结构体成员顺序确认,ABI 偏移未确认)。
   缓解:JNI 内槽位扫描 + `QueryInterface` 验证,决不盲取;失败路径 = wrap 失败、
   视频黑屏、播放继续(与 macOS interop 失败分支同行为);升级 Skiko 时跑冒烟测试回归。
   这与已上线的 `SkiaMetalInterop` 属同一风险等级。
3. **alpha 未定义**:macOS 实测 mpv 对不透明视频输出 undefined alpha,GL 路径用
   `glColorMask(F,F,F,T)+glClear` 修正;D3D11 没有逐通道 clear。
   对策(按序尝试):① mpv 选项 `background=color` + 不透明 `background-color`,让
   renderer 自己写 alpha=1;② 若不奏效,原生加一个 alpha-only 修正 pass
   (blend state `RenderTargetWriteMask=ALPHA` + 全屏三角形,一次性 ~60 行 HLSL/setup)。
   验证手段现成:冒烟测试的像素断言。
4. **BRT-wrapped surface 在 Compose RenderNode 下的行为**:直接 snapshot 不渲染的坑
   macOS 已踩过,blit+snapshot 模式已在生产验证 —— D3D12 侧沿用同一模式,风险低,
   但列入首个手动验证项。
5. **cherry-pick 到 0.41.0 的接口漂移**:`libmpv_gpu.h` 若在 0.41→master 间有变,
   回移量小(PR 对该文件只 +4 行);Phase 0.3 的最小 C 冒烟兜底验证。
6. **每帧 `ra_d3d11_wrap_tex`**(ring 轮换纹理导致 PR 的单纹理缓存每帧失效):
   开销为创建一个 RTV,可忽略;如实测有感,可向上游提议缓存 N 张(或我们 patch 中直接改)。

## 7. 参考

- mpv PR:https://github.com/mpv-player/mpv/pull/17764(head `kasper93/mpv@87316d3`)
- 新头文件:`include/mpv/render_d3d11.h`(PR 内)
- 微软 D3D11↔D3D12 互操作:`ID3D12Device::OpenSharedHandle`、
  `ID3D11Device5::OpenSharedFence`、`ID3D11Fence::CreateSharedHandle`(learn.microsoft.com)
- Skiko 0.9.37 源:`BackendRenderTarget.makeDirect3D`(`BackendRenderTarget.kt:51`)、
  `Direct3DRedrawer.kt`、`awtMain/cpp/windows/directXRedrawer.cc`(`DirectXDevice` 结构)
- 仓内对照实现:`mediamp-mpv/src/cpp/render_macos.mm`、
  `mediamp-mpv/src/desktopMain/kotlin/utils/SkiaMetalInterop.kt`、
  [dependency.md](dependency.md) "macOS 渲染与开发工作流"
- 先例:media_kit(libmpv GL-over-ANGLE→D3D11)、mpv issue #5979
