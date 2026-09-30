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
#include <thread>
#include <unordered_map>
#include <vector>
#include <jni.h>
#include <mpv/client.h>
#include <mpv/stream_cb.h>

#include "platform.h"
#include "log.h"
#include "desktop_renderer.h"

namespace mediampv {

class mpv_handle_t final {
public:
    explicit mpv_handle_t(JNIEnv *env, jobject app_context) {
        create(env, app_context);
    }
    ~mpv_handle_t();

    mpv_handle_t(const mpv_handle_t &) = delete;
    mpv_handle_t &operator=(const mpv_handle_t &) = delete;

    // Every thread the instance owns (event loop, render thread) calls this first, so
    // the handle registry can tell when the last reference is dropped on one of them.
    void bind_current_thread() const;
    bool is_current_thread_owned() const;

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

#ifdef MEDIAMPV_DESKTOP
    // The native render path (desktop_renderer.h): at most one per player, null before
    // it is created and after destroy_renderer(). Callers hold the returned reference
    // for the duration of a call; a renderer that was shut down meanwhile answers with
    // its "unavailable" values.
    std::shared_ptr<desktop_renderer> renderer();
    // Shuts the renderer down synchronously (render thread joined, GPU resources freed).
    bool destroy_renderer();
    // Packed frame state of the current renderer (kNoFrameState without one).
    uint64_t frame_state();

#ifdef _WIN32
    // Skiko's DirectXDevice pointer, recorded before create_d3d11_renderer so our
    // ID3D11Device is created on Skia's adapter (0 = no preference).
    bool set_consumer_device_hint(int64_t skiko_device_ptr);
    // D3D11 shared-texture ring (render_d3d11.cpp), also serving CPU readback consumers.
    bool create_d3d11_renderer();
    // OpenGL readback fallback (render_opengl_win.cpp), for Skiko's OpenGL redrawer.
    bool create_win_gl_renderer();
#endif
#ifdef __APPLE__
    // IOSurface/Metal ring (render_macos.mm).
    bool create_macos_renderer();
#endif
#ifdef MEDIAMPV_LINUX_DESKTOP
    // Context A is Skiko-owned. These borrowed GLX inputs create producer context B in
    // A's share group; a new identity rebuilds the complete native GL environment.
    bool attach_opengl_render_environment(
        int64_t display_ptr, int64_t share_context_ptr, int screen, uint64_t identity);
    // Shared-texture GLX ring (render_glx.cpp); requires an attached environment.
    bool create_glx_renderer();
#endif

#endif

    // For renderers and callbacks.
    mpv_handle *mpv() const { return handle_; }
    JavaVM *jvm() const { return jvm_; }
    // Calls the Kotlin RenderUpdateListener (outside any native lock).
    void notify_render_update();

    struct seekable_stream_entry;
    struct seekable_stream_cookie;

private:
    JavaVM *jvm_ = nullptr;
    mpv_handle *handle_ = nullptr;
    // Serializes the simple hot methods (command/set_option/get/set/observe/unobserve
    // property) that read handle_ and call mpv_* against destroy()'s
    // mpv_terminate_destroy + handle_=nullptr, closing the TOCTOU/use-after-free window
    // when teardown races an in-flight native call. Never nested with another lock in
    // those methods to avoid lock-order inversions (the
    // seekable-stream methods intentionally do NOT take it — they are ordered under
    // stream_registry_lock instead).
    std::mutex handle_lock_;

    jobject event_listener_ = nullptr;
    jobject render_update_listener_ = nullptr;
    // Guards both listener slots. Held only to swap a slot or take a local reference;
    // listeners are invoked after it is released (local_listener).
    std::mutex listener_lock_;
    // Serializes attach/detach_android_surface (Android wid option + Surface ref).
    std::mutex surface_access_lock_;

#ifdef __ANDROID__
    bool surface_attached_ = false;
    jobject surface_ = nullptr;
#endif

#ifdef MEDIAMPV_DESKTOP
    // renderer_lock_ guards the pointer only (held briefly by every consumer call);
    // renderer_setup_lock_ serializes creation and teardown, which take much longer.
    std::mutex renderer_lock_;
    std::mutex renderer_setup_lock_;
    std::shared_ptr<desktop_renderer> renderer_;
    void set_renderer(std::shared_ptr<desktop_renderer> renderer);
#ifdef _WIN32
    int64_t consumer_device_hint_ = 0;
#endif
#ifdef MEDIAMPV_LINUX_DESKTOP
    glx_environment_ref glx_environment_;
    bool glx_environment_attached_ = false;
#endif
#endif

    std::thread event_thread_;
    std::atomic_bool event_loop_request_exit{false};
    bool stream_protocol_registered_ = false;
    std::mutex stream_registry_lock_;
    std::unordered_map<std::string, std::shared_ptr<seekable_stream_entry>> seekable_streams_;

    void event_loop();
    bool ensure_stream_protocol_registered();
    int open_seekable_stream(const char *uri, mpv_stream_cb_info *info);
    static int open_seekable_stream(void *user_data, char *uri, mpv_stream_cb_info *info);
    void clear_event_listener(JNIEnv *env);
    void clear_render_update_listener(JNIEnv *env);
    // Swaps a listener slot to `global` (null clears it) and deletes the previous ref.
    void replace_listener(JNIEnv *env, jobject &slot, jobject global);
    // A local reference to the listener in `slot`, or null; see listener_lock_.
    jobject local_listener(JNIEnv *env, const jobject &slot);
    void clear_seekable_streams();
#ifdef __ANDROID__
    void clear_android_surface(JNIEnv *env);
#endif
};

} // namespace mediampv

#endif //MEDIAMP_MPV_HANDLE_T_H
