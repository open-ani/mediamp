#include "mpv_handle_t.h"
#include "method_cache.h"

namespace mediampv {

namespace {

struct attached_jni_env final {
    explicit attached_jni_env(JavaVM *vm) : vm(vm) {
        if (!vm) {
            return;
        }
        const jint get_env_result = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        if (get_env_result == JNI_OK) {
            return;
        }
        if (get_env_result == JNI_EDETACHED) {
#if defined(__ANDROID__)
            if (vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            }
#else
            if (vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) == JNI_OK) {
                attached = true;
            }
#endif
        }
    }

    ~attached_jni_env() {
        if (attached && vm) {
            vm->DetachCurrentThread();
        }
    }

    JNIEnv *env = nullptr;

private:
    JavaVM *vm = nullptr;
    bool attached = false;
};

bool clear_jni_exception(JNIEnv *env, const void *instance_handle, const char *context) {
    if (!env || !env->ExceptionCheck()) {
        return false;
    }

    // Describe + clear first, then log: logging goes through the JNI dispatcher, which
    // cannot run while an exception is pending. This surfaces the failure to the Kotlin
    // sink instead of silently swallowing it.
    env->ExceptionDescribe();
    env->ExceptionClear();
    LOG(instance_handle, LOG_LEVEL_ERROR, "JNI exception in %s", context);
    return true;
}

} // namespace

static void emit_property_change(
        JNIEnv *env,
        mpv_handle_t *instance,
        mpv_event_property *prop,
        jobject event_listener) {
    // jni_cache_classes is all-or-nothing, so a non-null class implies all the
    // onPropertyChange method IDs are non-null; checking it here keeps the CallVoidMethod
    // calls below from ever passing a null jmethodID (which would abort).
    if (!env || !prop || !prop->name || !event_listener || !jni_mediamp_clazz_EventListener) {
        return;
    }

    jstring prop_name = env->NewStringUTF(prop->name);
    jstring value = nullptr;

    switch (prop->format) {
        case MPV_FORMAT_NONE:
            env->CallVoidMethod(event_listener,
                                jni_mediamp_method_EventListener_onPropertyChange_NONE,
                                prop_name);
            break;
        case MPV_FORMAT_FLAG:
            if (!prop->data) break;
            env->CallVoidMethod(event_listener,
                                jni_mediamp_method_EventListener_onPropertyChange_FLAG,
                                prop_name,
                                (jboolean) (*(int *) prop->data != 0));
            break;
        case MPV_FORMAT_INT64:
            if (!prop->data) break;
            env->CallVoidMethod(event_listener,
                                jni_mediamp_method_EventListener_onPropertyChange_INT64,
                                prop_name,
                               (jlong) *(int64_t *) prop->data);
            break;
        case MPV_FORMAT_DOUBLE:
            if (!prop->data) break;
            env->CallVoidMethod(event_listener,
                                jni_mediamp_method_EventListener_onPropertyChange_DOUBLE,
                                prop_name,
                                (jdouble) *(double *) prop->data);
            break;
        case MPV_FORMAT_STRING:
            if (!prop->data || !*(const char **) prop->data) break;
            value = env->NewStringUTF(*(const char **) prop->data);
            env->CallVoidMethod(event_listener,
                                jni_mediamp_method_EventListener_onPropertyChange_STRING,
                                prop_name,
                                value);
            break;
        default:
            LOG(instance, LOG_LEVEL_DEBUG,
                "emit_property_change: unhandled property format %d for %s", prop->format, prop->name);
            break;
    }
    clear_jni_exception(env, instance, "EventListener.onPropertyChange");

    if (prop_name) env->DeleteLocalRef(prop_name);
    if (value) env->DeleteLocalRef(value);
}

// Forwards an mpv log message (MPV_EVENT_LOG_MESSAGE) to the Kotlin sink, preserving mpv's
// own prefix and log level (already on the shared mpv_log_level scale).
static void emit_log_message(mpv_handle_t *instance, mpv_event_log_message *message) {
    if (!message) {
        return;
    }
    log_forward(instance,
                message->log_level,
                message->prefix ? message->prefix : "",
                message->text ? message->text : "");
}

void *(mpv_handle_t::event_loop)(void *arg) {
    if (!jvm_ || !handle_) {
        LOG(this, LOG_LEVEL_ERROR,
            "[event_loop] jvm or mpv handle is not initialized; event loop will not start");
        return nullptr;
    }

    attached_jni_env attached_env(jvm_);
    JNIEnv *env = attached_env.env;
    if (!env) {
        LOG(this, LOG_LEVEL_ERROR,
            "[event_loop] failed to attach current thread; event loop will not start");
        return nullptr;
    }
    jni_cache_classes(env, this);

    LOG(this, LOG_LEVEL_V, "[event_loop] started");
    while (!event_loop_request_exit.load(std::memory_order_acquire)) {
        if (!handle_) {
            LOG(this, LOG_LEVEL_V, "[event_loop] mpv handle destroyed; event loop will stop");
            break;
        }

        mpv_event *event;
        mpv_event_property *event_property = nullptr;
        mpv_event_log_message *log_message = nullptr;

        // 不处理 NONE 事件
        if ((event = mpv_wait_event(handle_, -1.0))->event_id == MPV_EVENT_NONE) {
            continue;
        }

        if (event->event_id != MPV_EVENT_PROPERTY_CHANGE &&
            event->event_id != MPV_EVENT_LOG_MESSAGE &&
            jni_mediamp_method_EventListener_onEvent
        ) {
            env->CallVoidMethod(
                    event_listener_,
                    jni_mediamp_method_EventListener_onEvent,
                    static_cast<jint>(event->event_id));
            clear_jni_exception(env, this, "EventListener.onEvent");
        }

        switch (event->event_id) {
            case MPV_EVENT_PROPERTY_CHANGE:
                event_property = (mpv_event_property *) event->data;
                emit_property_change(env, this, event_property, event_listener_);
                break;
            case MPV_EVENT_LOG_MESSAGE:
                log_message = (mpv_event_log_message *) event->data;
                emit_log_message(this, log_message);
                break;
            case MPV_EVENT_IDLE:
                break;
            case MPV_EVENT_END_FILE: {
                auto *end_file = (mpv_event_end_file *) event->data;
                // reason != EOF/STOP with a non-zero error is a real playback failure.
                const int level = end_file->error != 0 ? LOG_LEVEL_WARN : LOG_LEVEL_INFO;
                LOG(this, level, "[event_loop] end-file: reason=%d error=%s",
                    end_file->reason, mpv_error_string(end_file->error));
                if (event_listener_ && jni_mediamp_method_EventListener_onEndFile) {
                    env->CallVoidMethod(
                        event_listener_,
                        jni_mediamp_method_EventListener_onEndFile,
                        static_cast<jint>(end_file->reason),
                        static_cast<jint>(end_file->error));
                    clear_jni_exception(env, this, "EventListener.onEndFile");
                }
                break;
            }
            case MPV_EVENT_SHUTDOWN:
                LOG(this, LOG_LEVEL_V, "[event_loop] shutdown");
                return nullptr;
            default:
                LOG(this, LOG_LEVEL_TRACE, "[event_loop] unhandled event: %d", event->event_id);
                break;
        }

    }
    LOG(this, LOG_LEVEL_V, "[event_loop] stopped");
    return nullptr;
}

} // namespace mediampv
