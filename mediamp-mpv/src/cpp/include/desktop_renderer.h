#ifndef MEDIAMP_DESKTOP_RENDERER_H
#define MEDIAMP_DESKTOP_RENDERER_H

#include "platform.h"

#ifdef MEDIAMPV_DESKTOP

#include <cstdint>
#include <memory>
#include <vector>

namespace mediampv {

class mpv_handle_t;

// Packed frame state shared by every path: generation(16) | latest_index(4, 0xF = none)
// | width(14) | height(14) | serial(16). Any change means there is something new to
// consume; a generation change means the buffers were reallocated.
constexpr uint64_t kNoFrameState = 0xFull << 44;

// One native render path of a player, behind the operations its Kotlin consumer uses:
// the GPU rings (surface_ring.h: render_d3d11.cpp, render_macos.mm, render_glx.cpp) and
// the Windows OpenGL readback fallback (render_opengl_win.cpp). A player owns at most one
// at a time. Every method may be called from any thread, also after shutdown(), which
// stops the render thread and frees GPU resources synchronously; later calls return
// their "unavailable" values.
class desktop_renderer {
public:
    virtual ~desktop_renderer() = default;

    virtual void shutdown() = 0;

    // Asynchronously (re)allocates the render target at width x height; <= 0 deactivates
    // it (frames are then drained). consumer_device is the consumer's render device
    // where the path has one: Skiko's DirectXDevice struct (D3D11), an MTLDevice (macOS).
    virtual bool set_surface_config(int width, int height, int64_t consumer_device) = 0;
    virtual uint64_t frame_state() = 0;
    virtual bool has_surface() = 0;
    // The latest frame as ARGB_8888 ints (0xAARRGGBB, row-major, top-down), alpha opaque.
    virtual bool read_surface_pixels(std::vector<uint32_t> &pixels, int &width, int &height) = 0;
    // The current frame rendered once more into a temporary width x height target, as
    // ARGB_8888 ints like read_surface_pixels: the video at the size the caller asks for
    // (its display size for screenshots) rather than the consumer's. Needs no ring, only
    // a frame mpv can redraw, so it also serves headless capture. Blocks the caller until
    // the render thread served the request (bounded).
    virtual bool render_frame_pixels(int width, int height, std::vector<uint32_t> &pixels) = 0;

    // GPU rings: the consumer-visible texture of ring buffer `index` for the current
    // generation, and the acknowledgement that the previous generation is unused.
    virtual int64_t buffer_texture(int index) { return 0; }
    virtual bool ack_retired_buffers() { return false; }

    // CPU readback paths: copies the latest frame (RGBA8, top-down, width*4 stride) into
    // dest if it is exactly width x height; returns its frame state, or 0.
    virtual uint64_t copy_latest_frame(void *dest, int width, int height) { return 0; }
    // D3D11 only: set_surface_config for consumers without a D3D12 device, which take
    // frames through copy_latest_frame instead.
    virtual bool set_readback_surface_config(int width, int height) { return false; }
};

// Factories: each returns null (after logging why) when the path cannot start.
#ifdef _WIN32
// consumer_device_hint: Skiko's DirectXDevice (0 = none); the D3D11 device is created on
// its adapter, since shared textures cannot be opened across adapters.
std::shared_ptr<desktop_renderer> create_d3d11_renderer(mpv_handle_t &owner, int64_t consumer_device_hint);
std::shared_ptr<desktop_renderer> create_win_gl_renderer(mpv_handle_t &owner);
#endif
#ifdef __APPLE__
std::shared_ptr<desktop_renderer> create_macos_renderer(mpv_handle_t &owner);
#endif
#ifdef MEDIAMPV_LINUX_DESKTOP
// Borrowed from Skiko's live GLX context A; the producer context is created in A's
// share group. identity changes whenever Skiko replaces that environment.
struct glx_environment_ref {
    int64_t display = 0;
    int64_t share_context = 0;
    int screen = 0;
    uint64_t identity = 0;
};
std::shared_ptr<desktop_renderer> create_glx_renderer(mpv_handle_t &owner, const glx_environment_ref &environment);
#endif

} // namespace mediampv

#endif // MEDIAMPV_DESKTOP

#endif // MEDIAMP_DESKTOP_RENDERER_H
