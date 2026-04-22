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

bool clear_jni_exception(JNIEnv *env, const char *context) {
    if (!env || !env->ExceptionCheck()) {
        return false;
    }

    LOG("JNI exception in %s\n", context);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

} // namespace

static void emit_property_change(JNIEnv *env, mpv_event_property *prop, jobject event_listener) {
    if (!env || !prop || !prop->name || !event_listener) {
        return;
    }

    jstring prop_name = env->NewStringUTF(prop->name);
    jstring value = nullptr;

    switch (prop->format) {
        case MPV_FORMAT_NONE:
            LOG("[event_loop] property change: %s", prop->name);
            env->CallVoidMethod(event_listener,
                                jni_mediamp_method_EventListener_onPropertyChange_NONE,
                                prop_name);
            break;
        case MPV_FORMAT_FLAG:
            if (!prop->data) break;
            LOG("[event_loop] property change: %s, %i", prop->name, *(int *) prop->data);
            env->CallVoidMethod(event_listener,
                                jni_mediamp_method_EventListener_onPropertyChange_FLAG,
                                prop_name,
                                (jboolean) (*(int *) prop->data != 0));
            break;
        case MPV_FORMAT_INT64:
            if (!prop->data) break;
            LOG("[event_loop] property change: %s, %lld", prop->name, *(int64_t *) prop->data);
            env->CallVoidMethod(event_listener,
                                jni_mediamp_method_EventListener_onPropertyChange_INT64,
                                prop_name,
                               (jlong) *(int64_t *) prop->data);
            break;
        case MPV_FORMAT_DOUBLE:
            if (!prop->data) break;
            LOG("[event_loop] property change: %s, %f", prop->name, *(double *) prop->data);
            env->CallVoidMethod(event_listener,
                                jni_mediamp_method_EventListener_onPropertyChange_DOUBLE,
                                prop_name,
                                (jdouble) *(double *) prop->data);
            break;
        case MPV_FORMAT_STRING:
            if (!prop->data || !*(const char **) prop->data) break;
            LOG("[event_loop] property change: %s, %s", prop->name, *(const char **) prop->data);
            value = env->NewStringUTF(*(const char **) prop->data);
            env->CallVoidMethod(event_listener,
                                jni_mediamp_method_EventListener_onPropertyChange_STRING,
                                prop_name,
                                value);
            break;
        default:
            LOG("emit_property_change: Unknown property update format received in callback: %d", prop->format);
            break;
    }
    clear_jni_exception(env, "EventListener.onPropertyChange");

    if (prop_name) env->DeleteLocalRef(prop_name);
    if (value) env->DeleteLocalRef(value);
}

static void emit_log_message(JNIEnv *env, mpv_event_log_message *message) {
    if (!env || !message || !jni_mediamp_clazz_MPVLogKt || !jni_mediamp_method_MPVLogKt_onNativeLog) {
        return;
    }

    const char *prefix_chars = message->prefix ? message->prefix : "";
    const char *text_chars = message->text ? message->text : "";

    jstring prefix = env->NewStringUTF(prefix_chars);
    jstring text = env->NewStringUTF(text_chars);
    if (!prefix || !text) {
        clear_jni_exception(env, "NewStringUTF(MPV log)");
        if (prefix) env->DeleteLocalRef(prefix);
        if (text) env->DeleteLocalRef(text);
        return;
    }

    env->CallStaticVoidMethod(
        jni_mediamp_clazz_MPVLogKt,
        jni_mediamp_method_MPVLogKt_onNativeLog,
        static_cast<jint>(message->log_level),
        prefix,
        text
    );
    clear_jni_exception(env, "MPVLogKt.onNativeLog");

    env->DeleteLocalRef(prefix);
    env->DeleteLocalRef(text);
}

void *(mpv_handle_t::event_loop)(void *arg) {
    if (!jvm_ || !handle_) {
        LOG("[event_loop] jvm or mpv handle_ is not initialized, event loop will not start");
        return nullptr;
    }

    attached_jni_env attached_env(jvm_);
    JNIEnv *env = attached_env.env;
    if (!env) {
        LOG("[event_loop] failed to attach current thread");
        return nullptr;
    }
    jni_cache_classes(env);

    LOG("[event_loop] event loop is started.");
    while (!event_loop_request_exit.load(std::memory_order_acquire)) {
        if (!handle_) {
            LOG("[event_loop] mpv handle_ is destroyed, event loop will stop");
            break;
        }

        mpv_event *event;
        mpv_event_property *event_property = nullptr;
        mpv_event_log_message *log_message = nullptr;

        // 不处理 NONE 事件
        if ((event = mpv_wait_event(handle_, -1.0))->event_id == MPV_EVENT_NONE) {
            continue;
        }

        switch (event->event_id) {
            case MPV_EVENT_PROPERTY_CHANGE:
                event_property = (mpv_event_property *) event->data;
                emit_property_change(env, event_property, event_listener_);
                break;
            case MPV_EVENT_LOG_MESSAGE:
                log_message = (mpv_event_log_message *) event->data;
                emit_log_message(env, log_message);
                break;
            case MPV_EVENT_IDLE:
                LOG("[event_loop] idle");
                break;
            case MPV_EVENT_SHUTDOWN:
                LOG("[event_loop] shutdown");
                return nullptr;
            default:
                LOG("[event_loop] unhandled event: %d", event->event_id);
                break;
        }

    }
    LOG("[event_loop] event loop is stopped.");
    return nullptr;
}

} // namespace mediampv
