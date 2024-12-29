#include <iostream>
#include "mpv_handle_t.h"
#include "method_cache.h"
#include "compatible_thread.h"
#include "global_lock.h"

extern "C" {
#include <libavcodec/jni.h>
}

namespace mediampv {

CREATE_LOCK(global_guard);
JavaVM *global_jvm = nullptr;
    
void mpv_handle_t::create(JNIEnv *env, jobject app_context) {
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
    if (event_listener_) {
        env->DeleteGlobalRef(event_listener_);
        event_listener_ = nullptr;
    }
    
    event_listener_ = env->NewGlobalRef(listener);
    mediampv::jni_cache_classes(env);
    
    return true;
}

bool mpv_handle_t::destroy(JNIEnv *env) {
    event_loop_request_exit = true;
    
    if (!handle_) {
        LOG("mpv handle is not created when destroy");
        return false;
    }
    mpv_wakeup(handle_);
    
    if (!event_thread_) {
        LOG("event thread is not created when destroy mpv handle");
        return false;
    }
    event_thread_->join();
    
    if (event_listener_) env->DeleteGlobalRef(event_listener_);
    mpv_terminate_destroy(handle_);
    
    return true;
}

} // namespace mediampv