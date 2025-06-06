#include <iostream>
#include "mpv_handle_t.h"
#include "method_cache.h"
#include "compatible_thread.h"
#include "global_lock.h"
#include <mpv/render_gl.h>

#ifdef _WIN32
#include <windows.h>
#endif

extern "C" {
#include <libavcodec/jni.h>
}

#define CHECK_HANDLE() if (!handle_) { \
    LOG("mpv handle is not created when %s", __FUNCTION__); \
    return false; \
}

namespace mediampv {

CREATE_LOCK(global_guard);
JavaVM *global_jvm = nullptr;
    
void mpv_handle_t::create(JNIEnv *env, jobject app_context) {
    FP;
    LOCK(global_guard);
    
    if (!global_jvm) {
        env->GetJavaVM(&global_jvm);
        if (!global_jvm) {
            LOG("failed to get current jvm");
            exit(1); // TODO: don't exit
        }

        av_jni_set_java_vm(global_jvm, &app_context);
    }
    
    jvm_ = global_jvm;
    handle_ = mpv_create();

    // use terminal log level but request verbose messages
    // this way --msg-level can be used to adjust later
    mpv_request_log_messages(handle_, "terminal-default");
    mpv_set_option_string(handle_, "msg-level", "all=v");
}

bool mpv_handle_t::initialize() {
    FP;
    
    if (!handle_) return false;
    if (mpv_initialize(handle_) < 0) {
        LOG("failed to initialize mpv");
        return false;
    }
    
    event_thread_ = std::make_shared<mediampv::compatible_thread>([&] { event_loop(0); });
    if (!event_thread_->create()) {
        LOG("failed to create event thread");
        return false;
    }
    
    return true;
}

bool mpv_handle_t::set_event_listener(JNIEnv *env, jobject listener) {
    FP;
    
    if (event_listener_ && *event_listener_) {
        env->DeleteGlobalRef(*event_listener_);
        event_listener_ = nullptr;
    }
    mediampv::jni_cache_classes(env);
    
    if (env->IsInstanceOf(listener, mediampv::jni_mediamp_clazz_EventListener) != JNI_TRUE) {
        LOG("listener is not an instance of EventListener");
        return false;
    }

    if (!event_listener_) event_listener_ = new jobject;
    *event_listener_ = env->NewGlobalRef(listener);
    
    return true;
}

bool mpv_handle_t::command(const char **args) {
    FP;
    CHECK_HANDLE()
    return mpv_command(handle_, args) >= 0;
}

bool mpv_handle_t::set_option(const char *key, const char *value) {
    FP;
    CHECK_HANDLE()
    return mpv_set_option_string(handle_, key, value);
}

bool mpv_handle_t::get_property(const char *name, mpv_format format, void *out_result) {
    FP;
    CHECK_HANDLE()
    return mpv_get_property(handle_, name, format, out_result) >= 0;
}

bool mpv_handle_t::set_property(const char *name, mpv_format format, void *in_value) {
    FP;
    CHECK_HANDLE()
    return mpv_set_property(handle_, name, format, in_value) >= 0;
}

bool mpv_handle_t::observe_property(const char *property, mpv_format format, uint64_t reply_data) {
    FP;
    CHECK_HANDLE()
    return mpv_observe_property(handle_, reply_data, property, format) >= 0;
}

bool mpv_handle_t::unobserve_property(uint64_t reply_data) {
    FP;
    CHECK_HANDLE()
    return mpv_unobserve_property(handle_, reply_data) >= 0;
}

CREATE_LOCK(surface_access_lock);

bool mpv_handle_t::attach_android_surface(JNIEnv *env, jobject surface) {
    FP;
    LOCK(surface_access_lock);
    CHECK_HANDLE()

#ifdef __ANDROID__
    if (surface_attached_) detach_android_surface(env);
    if (env->IsInstanceOf(surface, mediampv::jni_mediamp_clazz_android_Surface) != JNI_TRUE) {
        LOG("surface is not instance of android.view.Surface");
        return false;
    }
    
    jobject ref = env->NewGlobalRef(surface);
    int64_t wid = (int64_t)(intptr_t) ref;
    surface_ = ref;
    surface_attached_ = mpv_set_option(handle_, "wid", MPV_FORMAT_INT64, &wid) >= 0;
    
    return surface_attached_;
#else
    LOG("attach_android_surface is only implemented on Android");
    return false;
#endif
}

bool mpv_handle_t::detach_android_surface(JNIEnv *env) {
    FP;
    LOCK(surface_access_lock);
    CHECK_HANDLE()
    
#ifdef __ANDROID__
    if (!surface_attached_) return false;
    
    int64_t wid = 0;
    bool result = mpv_set_option(handle_, "wid", MPV_FORMAT_INT64, (void*) &wid);
    env->DeleteGlobalRef(surface_);
    surface_attached_ = false;
    
    return result;
#else
    LOG("detach_android_surface is only implemented on Android");
    return false;
#endif
}

#ifdef _WIN32
    bool mpv_handle_t::attach_window_surface(int64_t wid) {
        FP;
        CHECK_HANDLE();
        return mpv_set_option(handle_, "wid", MPV_FORMAT_INT64, &wid) >= 0;
    }
    
    bool mpv_handle_t::detach_window_surface() {
        FP;
        CHECK_HANDLE();
        int64_t wid = 0;
        return mpv_set_option(handle_, "wid", MPV_FORMAT_INT64, &wid) >= 0;
    }
#endif

    bool mpv_handle_t::create_render_context() {
        FP;
        CHECK_HANDLE()

#ifdef _WIN32
        if (render_context_)
            return true;
    
        mpv_opengl_init_params gl_init_params{};
        gl_init_params.get_proc_address = (void *(*)(void *, const char *)) wglGetProcAddress;
        gl_init_params.get_proc_address_ctx = nullptr;
    
        mpv_render_param params[] = {
                {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
                {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init_params},
                {MPV_RENDER_PARAM_INVALID, nullptr},
        };
    
        if (mpv_render_context_create(&render_context_, handle_, params) < 0) {
            render_context_ = nullptr;
            return false;
        }
        return true;
#else
        return false;
#endif
    }

    bool mpv_handle_t::destroy_render_context() {
        FP;
        if (!render_context_)
            return false;

        mpv_render_context_free(render_context_);
        render_context_ = nullptr;
        return true;
    }

    bool mpv_handle_t::render_frame(int fbo, int w, int h) {
        FP;
        if (!render_context_)
            return false;

        mpv_opengl_fbo fbo_params{fbo, w, h, 0};
        mpv_render_param params[] = {
                {MPV_RENDER_PARAM_OPENGL_FBO, &fbo_params},
                {MPV_RENDER_PARAM_INVALID,    nullptr},
        };
        mpv_render_context_render(render_context_, params);
        return true;
    }

bool mpv_handle_t::destroy(JNIEnv *env) {
    FP;
    CHECK_HANDLE()
    
    event_loop_request_exit = true;
    mpv_wakeup(handle_);
    
    if (!event_thread_) {
        LOG("event thread is not created when destroy mpv handle");
        return false;
    }
    event_thread_->join();
    
    if (event_listener_) env->DeleteGlobalRef(*event_listener_);
    mpv_terminate_destroy(handle_);
    
    return true;
}

} // namespace mediampv