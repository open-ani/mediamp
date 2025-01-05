//
// Created by StageGuard on 12/28/2024.
//

#ifndef MEDIAMP_MPV_HANDLE_T_H
#define MEDIAMP_MPV_HANDLE_T_H

#include <iostream>
#include <jni.h>
#include <mpv/client.h>
#include "compatible_thread.h"
#include "log.h"

namespace mediampv {

class mpv_handle_t final {
public:
    explicit mpv_handle_t(JNIEnv *env, jobject app_context) {
        create(env, app_context);
    }
    ~mpv_handle_t() = default;
    
    void create(JNIEnv *env, jobject app_context);
    bool initialize();
    bool set_event_listener(JNIEnv *env, jobject listener);
    bool destroy(JNIEnv *env);
    
    bool command(const char **args);
    bool set_option(const char *key, const char *value);
    bool get_property(const char *name, mpv_format format, void *out_result);
    bool set_property(const char *name, mpv_format format, void *in_value);
    bool observe_property(const char *property, mpv_format format, uint64_t reply_data);
    bool unobserve_property(uint64_t reply_data);
    
    bool attach_android_surface(JNIEnv *env, jobject surface);
    bool detach_android_surface(JNIEnv *env);
    
private:
    JavaVM *jvm_;
    mpv_handle *handle_;
    
    jobject *event_listener_ = nullptr;

#ifdef __ANDROID__
    bool surface_attached_ = false;
    jobject surface_;
#endif

    std::shared_ptr<mediampv::compatible_thread> event_thread_;
    bool event_loop_request_exit = false;

    void *event_loop(void *arg);
};

} // namespace mediampv

#endif //MEDIAMP_MPV_HANDLE_T_H
