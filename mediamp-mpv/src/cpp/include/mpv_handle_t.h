//
// Created by StageGuard on 12/28/2024.
//

#ifndef MEDIAMP_MPV_HANDLE_T_H
#define MEDIAMP_MPV_HANDLE_T_H

#include <iostream>
#include <atomic>
#include <memory>
#include <string>
#include <unordered_map>
#include <jni.h>
#include <mpv/client.h>
#include <mpv/render_gl.h>
#include <mpv/stream_cb.h>

#ifdef _WIN32
#include <windows.h>
#include <gl/GL.h>
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
    // Render API (Windows x64 only)
    bool create_render_context(HDC device, HGLRC context);
    bool destroy_render_context();

    GLuint create_texture(int width, int height);
    bool release_texture();

    bool render_frame();
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
    HGLRC context_ = nullptr;
    HDC device_ = nullptr;

    GLuint fbo_ = 0, texture_ = 0;
    int width_ = 0, height_ = 0;
    CREATE_LOCK(texture_lock);
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
};

} // namespace mediampv

#endif //MEDIAMP_MPV_HANDLE_T_H
