//
// Created by StageGuard on 12/28/2024.
//

#ifndef MEDIAMP_MPV_HANDLE_T_H
#define MEDIAMP_MPV_HANDLE_T_H

#include <iostream>
#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <jni.h>
#include <mpv/client.h>
#include <mpv/render_gl.h>
#include <mpv/stream_cb.h>

#ifdef _WIN32
#include <windows.h>
// COM interfaces used by the D3D11 render path (render_d3d11.cpp); forward-declared so
// this header does not pull d3d11.h/d3d12.h into every translation unit.
struct ID3D11Device;
struct ID3D11DeviceContext;
struct ID3D11Query;
struct ID3D11Texture2D;
struct ID3D12Device;
struct ID3D12Resource;
#endif
#include "compatible_thread.h"
#include "global_lock.h"
#include "log.h"

namespace mediampv {

class mpv_handle_t final {
public:
    explicit mpv_handle_t(JNIEnv *env, jobject app_context) {
        create(env, app_context);
    }
    ~mpv_handle_t();

    void create(JNIEnv *env, jobject app_context);
    bool initialize();
    bool set_event_listener(JNIEnv *env, jobject listener);
    bool set_render_update_listener(JNIEnv *env, jobject listener);
    bool destroy(JNIEnv *env);

    bool command(const char **args);
    bool set_option(const char *key, const char *value);
    bool get_property(const char *name, mpv_format format, void *out_result);
    bool set_property(const char *name, mpv_format format, void *in_value);
    bool observe_property(const char *property, mpv_format format, uint64_t reply_data);
    bool unobserve_property(uint64_t reply_data);
    bool register_seekable_input(JNIEnv *env, jobject seekable_input, const char *uri, int64_t size);
    bool unregister_seekable_input(const char *uri);

    bool attach_android_surface(JNIEnv *env, jobject surface);
    bool detach_android_surface(JNIEnv *env);

#ifdef __ANDROID__
    bool attach_window_surface(int64_t wid);
    bool detach_window_surface();
#endif

#ifdef _WIN32
    // Render API (Windows): a dedicated native render thread drives mpv through the
    // libmpv D3D11 render API (our own ID3D11Device) into a ring of shared textures;
    // each texture is also opened on the consumer-provided D3D12 device (Skia's device)
    // as an ID3D12Resource via NT shared handles. Consumers never render; they read the
    // packed frame state and sample the latest buffer. Implemented in render_d3d11.cpp.
    bool create_render_context();
    bool destroy_render_context();

    // Requests the render thread to (re)allocate the buffer ring at width x height,
    // opening each texture on the ID3D12Device extracted from skiko_device_ptr (a
    // pointer to Skiko's native DirectXDevice struct; 0 = D3D11-only, no D3D12 side —
    // used headless). width/height <= 0 deactivates the surface (frames are then
    // drained without rendering). Asynchronous: returns immediately, the swap happens
    // between frames on the render thread.
    bool set_surface_config(int width, int height, int64_t skiko_device_ptr);

    // Packed frame state: generation(16) | latest_index(4, 0xF = none) | width(14) |
    // height(14) | serial(16). Any change means there is something new to consume; a
    // generation change means the buffer ring was reallocated (re-wrap textures, then
    // call ack_retired_buffers()).
    uint64_t get_frame_state();
    // ID3D12Resource* of ring buffer `index` for the current generation (0 if the ring
    // has no D3D12 side). The pointer stays valid until the generation is retired and
    // acked, or the surface is deactivated.
    int64_t get_buffer_texture(int index);
    // Consumer no longer references the previous generation; its buffers may be freed.
    bool ack_retired_buffers();

    bool has_d3d11_surface();
    bool save_surface_png(const char *path);
#endif

#ifdef __APPLE__
    // Render API (macOS): a dedicated native render thread drives mpv through OpenGL
    // (offscreen CGL context) into a ring of IOSurface-backed FBOs; each IOSurface is
    // also wrapped as an MTLTexture on the consumer-provided MTLDevice (Skia's device).
    // Consumers never render; they read the packed frame state and sample the latest
    // buffer. Implemented in render_macos.mm.
    bool create_render_context();
    bool destroy_render_context();

    // Requests the render thread to (re)allocate the buffer ring at width x height with
    // MTLTextures on mtl_device_ptr (0 = system default device). width/height <= 0
    // deactivates the surface (frames are then drained without rendering). Asynchronous:
    // returns immediately, the swap happens between frames on the render thread.
    bool set_surface_config(int width, int height, int64_t mtl_device_ptr);

    // Packed frame state: generation(16) | latest_index(4, 0xF = none) | width(14) |
    // height(14) | serial(16). Any change means there is something new to consume; a
    // generation change means the buffer ring was reallocated (re-wrap textures, then
    // call ack_retired_buffers()).
    uint64_t get_frame_state();
    // Retained id<MTLTexture> pointer of ring buffer `index` for the current generation.
    int64_t get_buffer_texture(int index);
    // Consumer no longer references the previous generation; its buffers may be freed.
    bool ack_retired_buffers();

    bool has_metal_surface();
    bool save_surface_png(const char *path);
#endif

    struct seekable_stream_entry;
    struct seekable_stream_cookie;

private:
    JavaVM *jvm_ = nullptr;
    mpv_handle *handle_ = nullptr;

    jobject event_listener_ = nullptr;
    jobject render_update_listener_ = nullptr;
    CREATE_LOCK(render_update_listener_lock);

#ifdef __ANDROID__
    bool surface_attached_ = false;
    jobject surface_ = nullptr;
#endif

#ifdef _WIN32
    mpv_render_context *render_context_ = nullptr;
    // mpv renders on our own D3D11 device; the device's immediate context is put in
    // multithread-protected mode so save_surface_png (JNI thread) can copy/map while
    // the render thread is inside mpv_render_context_render.
    ID3D11Device *d3d_device_ = nullptr;
    ID3D11DeviceContext *d3d_context_ = nullptr;
    ID3D11Query *flush_query_ = nullptr;  // D3D11_QUERY_EVENT, the glFinish equivalent

    // Triple-buffered shared-texture ring. All D3D11 work and all buffer mutation
    // happens on the render thread; consumers only read the packed frame_state_ and the
    // ID3D12Resource pointers. Rationale for 3 buffers: the render thread writes
    // (latest+1)%3 while Skia may still have sampling of both the published latest and
    // the previous frame in flight on its own GPU timeline.
    static constexpr int kD3D11BufferCount = 3;
    struct d3d11_buffer {
        ID3D11Texture2D *texture = nullptr;    // render target on d3d_device_
        HANDLE shared_handle = nullptr;        // NT handle from CreateSharedHandle
        ID3D12Resource *d3d12_resource = nullptr;  // opened on the consumer's device, may be null
    };
    d3d11_buffer buffers_[kD3D11BufferCount];
    // Previous generation, kept alive until the consumer re-wrapped and acked, so a
    // consumer that observed the old generation can still sample it during the swap.
    d3d11_buffer retired_buffers_[kD3D11BufferCount];
    // Consumer-side D3D12 device (owned reference), extracted from Skiko's native
    // DirectXDevice struct; null while the ring is headless (device ptr 0).
    ID3D12Device *skia_device_ = nullptr;
    bool has_retired_buffers_ = false;
    bool buffers_allocated_ = false;
    int buffer_width_ = 0, buffer_height_ = 0;
    int64_t buffer_device_ptr_ = 0;
    uint32_t buffer_generation_ = 0;
    uint64_t frame_serial_ = 0;
    int latest_index_ = -1;
    std::atomic<uint64_t> frame_state_{0xFull << 44};  // "no buffer" sentinel

    // Requests to the render thread; guarded by render_mutex_.
    bool config_pending_ = false;
    int pending_width_ = 0, pending_height_ = 0;
    int64_t pending_device_ptr_ = 0;
    bool retire_ack_pending_ = false;
    bool render_pending_ = false;
    bool render_quit_ = false;
    std::mutex render_mutex_;
    std::condition_variable render_cv_;
    void *render_thread_ = nullptr;  // std::thread*, owned (render_d3d11.cpp)

    void signal_render_update();
    void start_render_thread();
    void stop_render_thread();
    void render_thread_loop();
    // The helpers below assume render_mutex_ is held (except render_into and
    // drain_one_frame, which run unlocked on the render thread).
    bool apply_config_locked();
    bool allocate_buffer(d3d11_buffer &buffer, int width, int height);
    void destroy_buffer_ring(d3d11_buffer *ring);
    void publish_state_locked();
    bool render_into(const d3d11_buffer &buffer);
    void drain_one_frame();
    bool wait_for_gpu();  // End(flush_query_) + poll; render thread only
#endif

#ifdef __APPLE__
    mpv_render_context *render_context_ = nullptr;
    void *cgl_context_ = nullptr;  // CGLContextObj

    // Triple-buffered IOSurface ring. All GL work and all buffer mutation happens on
    // the render thread (which keeps the CGL context current for its whole lifetime);
    // consumers only read the packed frame_state_ and the retained MTLTexture pointers.
    // Rationale for 3 buffers: the render thread writes (latest+1)%3 while Skia may
    // still have sampling of both the published latest and the previous frame in
    // flight on its own GPU timeline.
    static constexpr int kMacosBufferCount = 3;
    struct macos_buffer {
        void *io_surface = nullptr;   // IOSurfaceRef
        void *mtl_texture = nullptr;  // retained id<MTLTexture>
        uint32_t texture = 0;         // GL_TEXTURE_RECTANGLE bound to the IOSurface
        uint32_t fbo = 0;
    };
    macos_buffer buffers_[kMacosBufferCount];
    // Previous generation, kept alive until the consumer re-wrapped and acked, so a
    // consumer that observed the old generation can still sample it during the swap.
    macos_buffer retired_buffers_[kMacosBufferCount];
    bool has_retired_buffers_ = false;
    bool buffers_allocated_ = false;
    int buffer_width_ = 0, buffer_height_ = 0;
    int64_t buffer_device_ptr_ = 0;
    uint32_t buffer_generation_ = 0;
    uint64_t frame_serial_ = 0;
    int latest_index_ = -1;
    std::atomic<uint64_t> frame_state_{0xFull << 44};  // "no buffer" sentinel

    // Requests to the render thread; guarded by render_mutex_.
    bool config_pending_ = false;
    int pending_width_ = 0, pending_height_ = 0;
    int64_t pending_device_ptr_ = 0;
    bool retire_ack_pending_ = false;
    bool render_pending_ = false;
    bool render_quit_ = false;
    std::mutex render_mutex_;
    std::condition_variable render_cv_;
    void *render_thread_ = nullptr;  // std::thread*, owned (render_macos.mm)

    void signal_render_update();
    void start_render_thread();
    void stop_render_thread();
    void render_thread_loop();
    // The helpers below assume the CGL context is current on the calling thread and
    // render_mutex_ is held (except render_into/drain_one_frame, which run unlocked).
    bool apply_config_locked();
    bool allocate_buffer(macos_buffer &buffer, int width, int height, void *mtl_device);
    void destroy_buffer_ring(macos_buffer *ring);
    void publish_state_locked();
    bool render_into(const macos_buffer &buffer);
    void drain_one_frame();
#endif

    std::shared_ptr<mediampv::compatible_thread> event_thread_;
    std::atomic_bool event_loop_request_exit{false};
    bool stream_protocol_registered_ = false;
    CREATE_LOCK(stream_registry_lock);
    std::unordered_map<std::string, std::shared_ptr<seekable_stream_entry>> seekable_streams_;

    void *event_loop(void *arg);
    bool ensure_stream_protocol_registered();
    int open_seekable_stream(const char *uri, mpv_stream_cb_info *info);
    static int open_seekable_stream(void *user_data, char *uri, mpv_stream_cb_info *info);
    static void on_render_update(void *context);
    void clear_event_listener(JNIEnv *env);
    void clear_render_update_listener(JNIEnv *env);
    void notify_render_update();
    void clear_seekable_streams();
#ifdef __ANDROID__
    void clear_android_surface(JNIEnv *env);
#endif
#ifdef _WIN32
    void cleanup_render_resources();
#endif
#ifdef __APPLE__
    void cleanup_render_resources();
#endif
};

} // namespace mediampv

#endif //MEDIAMP_MPV_HANDLE_T_H
