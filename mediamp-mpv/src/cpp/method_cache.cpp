#include <mutex>

#define UTIL_EXTERN
#include "method_cache.h"
#include "log.h"

namespace mediampv {

namespace {

std::mutex jni_cache_mutex;
bool jni_class_cached = false;

bool clear_jni_exception(JNIEnv *env, const char *context) {
    if (!env || !env->ExceptionCheck()) {
        return false;
    }

    LOG("JNI exception in %s\n", context);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

jclass find_global_class(JNIEnv *env, const char *name) {
    jclass local_class = env->FindClass(name);
    if (!local_class || clear_jni_exception(env, name)) {
        return nullptr;
    }

    auto global_class = reinterpret_cast<jclass>(env->NewGlobalRef(local_class));
    env->DeleteLocalRef(local_class);
    if (!global_class || clear_jni_exception(env, name)) {
        return nullptr;
    }

    return global_class;
}

jmethodID find_method(JNIEnv *env, jclass clazz, const char *name, const char *signature) {
    jmethodID method = env->GetMethodID(clazz, name, signature);
    if (!method) {
        clear_jni_exception(env, name);
    }
    return method;
}

void delete_global_ref(JNIEnv *env, jclass &clazz) {
    if (env && clazz) {
        env->DeleteGlobalRef(clazz);
        clazz = nullptr;
    }
}

} // namespace

void jni_cache_classes(JNIEnv *env) {
    if (!env) {
        return;
    }

    std::lock_guard<std::mutex> guard(jni_cache_mutex);
    if (jni_class_cached) {
        return;
    }

    jclass event_listener_class = find_global_class(env, "org/openani/mediamp/mpv/EventListener");
    jclass seekable_input_class = find_global_class(env, "org/openani/mediamp/io/SeekableInput");
#ifdef __ANDROID__
    jclass surface_class = find_global_class(env, "android/view/Surface");
#endif
    if (!event_listener_class || !seekable_input_class
#ifdef __ANDROID__
        || !surface_class
#endif
    ) {
        delete_global_ref(env, event_listener_class);
        delete_global_ref(env, seekable_input_class);
#ifdef __ANDROID__
        delete_global_ref(env, surface_class);
#endif
        return;
    }

    jmethodID on_property_change_none =
            find_method(env, event_listener_class, "onPropertyChange", "(Ljava/lang/String;)V");
    jmethodID on_property_change_flag =
            find_method(env, event_listener_class, "onPropertyChange", "(Ljava/lang/String;Z)V");
    jmethodID on_property_change_int64 =
            find_method(env, event_listener_class, "onPropertyChange", "(Ljava/lang/String;J)V");
    jmethodID on_property_change_double =
            find_method(env, event_listener_class, "onPropertyChange", "(Ljava/lang/String;D)V");
    jmethodID on_property_change_string =
            find_method(env, event_listener_class, "onPropertyChange", "(Ljava/lang/String;Ljava/lang/String;)V");
    jmethodID seekable_input_read =
            find_method(env, seekable_input_class, "read", "([BII)I");
    jmethodID seekable_input_seek_to =
            find_method(env, seekable_input_class, "seekTo", "(J)V");
    jmethodID seekable_input_close =
            find_method(env, seekable_input_class, "close", "()V");

    if (!on_property_change_none ||
        !on_property_change_flag ||
        !on_property_change_int64 ||
        !on_property_change_double ||
        !on_property_change_string ||
        !seekable_input_read ||
        !seekable_input_seek_to ||
        !seekable_input_close) {
        delete_global_ref(env, event_listener_class);
        delete_global_ref(env, seekable_input_class);
#ifdef __ANDROID__
        delete_global_ref(env, surface_class);
#endif
        return;
    }

    jni_mediamp_clazz_EventListener = event_listener_class;
    jni_mediamp_method_EventListener_onPropertyChange_NONE = on_property_change_none;
    jni_mediamp_method_EventListener_onPropertyChange_FLAG = on_property_change_flag;
    jni_mediamp_method_EventListener_onPropertyChange_INT64 = on_property_change_int64;
    jni_mediamp_method_EventListener_onPropertyChange_DOUBLE = on_property_change_double;
    jni_mediamp_method_EventListener_onPropertyChange_STRING = on_property_change_string;
    jni_mediamp_clazz_SeekableInput = seekable_input_class;
    jni_mediamp_method_SeekableInput_read = seekable_input_read;
    jni_mediamp_method_SeekableInput_seekTo = seekable_input_seek_to;
    jni_mediamp_method_SeekableInput_close = seekable_input_close;
#ifdef __ANDROID__
    jni_mediamp_clazz_android_Surface = surface_class;
#endif

    jni_class_cached = true;
}
    
} // namespace mediampv
