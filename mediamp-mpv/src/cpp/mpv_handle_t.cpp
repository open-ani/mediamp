#include <iostream>
#include "mpv_handle_t.h"
#include "method_cache.h"
#include "compatible_thread.h"
#include "global_lock.h"
#include <vector>
#include <chrono>
#include <thread>

extern "C" {
#include <libavcodec/jni.h>
#include <mpv/render.h>
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

bool mpv_handle_t::attach_buffer_renderer(JNIEnv *env, jobject renderer) {
    FP;
#if defined(_WIN32)
    CHECK_HANDLE();
    if (render_context_)
        detach_buffer_renderer(env);

    jni_cache_classes(env);
    if (env->IsInstanceOf(renderer, mediampv::jni_mediamp_clazz_MpvBufferRenderer) != JNI_TRUE) {
        LOG("renderer is not MpvBufferRenderer");
        return false;
    }

    buffer_renderer_ = env->NewGlobalRef(renderer);

    mpv_render_param params[] = {
            {MPV_RENDER_PARAM_API_TYPE, const_cast<char*>(MPV_RENDER_API_TYPE_SW)},
            {MPV_RENDER_PARAM_ADVANCED_CONTROL, &(int){1}},
            {0}
    };
    if (mpv_render_context_create(&render_context_, handle_, params) < 0) {
        env->DeleteGlobalRef(buffer_renderer_);
        buffer_renderer_ = nullptr;
        return false;
    }

    mpv_render_context_set_update_callback(render_context_, [](void *ctx) {
        auto *self = static_cast<mpv_handle_t *>(ctx);
        self->render_update_ = true;
    }, this);

    render_loop_request_exit = false;
    render_thread_ = std::make_shared<mediampv::compatible_thread>([this] { render_loop(nullptr); });
    return render_thread_->create();
#else
    (void)env; (void)renderer;
    LOG("attach_buffer_renderer is only implemented on Windows");
    return false;
#endif
}

bool mpv_handle_t::detach_buffer_renderer(JNIEnv *env) {
    FP;
#if defined(_WIN32)
    if (!render_context_)
        return false;
    render_loop_request_exit = true;
    render_update_ = true;
    mpv_wakeup(handle_);
    if (render_thread_)
        render_thread_->join();
    render_thread_.reset();
    mpv_render_context_set_update_callback(render_context_, nullptr, nullptr);
    mpv_render_context_free(render_context_);
    render_context_ = nullptr;
    if (buffer_renderer_) {
        env->DeleteGlobalRef(buffer_renderer_);
        buffer_renderer_ = nullptr;
    }
    return true;
#else
    (void)env;
    LOG("detach_buffer_renderer is only implemented on Windows");
    return false;
#endif
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

void *mpv_handle_t::render_loop(void *arg) {
#if defined(_WIN32)
    (void)arg;
    if (!jvm_ || !render_context_)
        return nullptr;

    JNIEnv *env = nullptr;
    if (jvm_->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) != JNI_OK)
        return nullptr;

    std::vector<uint8_t> buffer;
    while (!render_loop_request_exit) {
        if (!render_context_)
            break;
        if (!render_update_) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }
        render_update_ = false;
        uint64_t flags = mpv_render_context_update(render_context_);
        if (flags & MPV_RENDER_UPDATE_FRAME) {
            int64_t w = 0, h = 0;
            get_property("dwidth", MPV_FORMAT_INT64, &w);
            get_property("dheight", MPV_FORMAT_INT64, &h);
            if (w > 0 && h > 0) {
                size_t stride = (size_t)w * 4;
                buffer.resize((size_t)w * (size_t)h * 4);
                int size[2] = {(int)w, (int)h};
                mpv_render_param params[] = {
                        {MPV_RENDER_PARAM_SW_SIZE, size},
                        {MPV_RENDER_PARAM_SW_FORMAT, (void *)"0rgb"},
                        {MPV_RENDER_PARAM_SW_STRIDE, &stride},
                        {MPV_RENDER_PARAM_SW_POINTER, buffer.data()},
                        {0}
                };
                if (mpv_render_context_render(render_context_, params) >= 0) {
                    jbyteArray arr = env->NewByteArray(buffer.size());
                    env->SetByteArrayRegion(arr, 0, buffer.size(), reinterpret_cast<jbyte*>(buffer.data()));
                    env->CallVoidMethod(buffer_renderer_, jni_mediamp_method_MpvBufferRenderer_onFrame,
                                       (jint)w, (jint)h, arr);
                    env->DeleteLocalRef(arr);
                    mpv_render_context_report_swap(render_context_);
                }
            }
        }
    }
    jvm_->DetachCurrentThread();
#endif
    return nullptr;
}

} // namespace mediampv