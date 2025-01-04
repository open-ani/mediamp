#include <iostream>
#include <jni.h>
#include <cstdio>
#include "method_cache.h"
#include "mpv_handle_t.h"

#define FN(name) Java_org_openani_mediamp_mpv_MPVHandleKt_##name
#define FN_ANDROID(name) Java_org_openani_mediamp_mpv_MPVHandleAndroid_##name

extern "C" {
    JNIEXPORT jboolean JNICALL FN(nGlobalInit)(JNIEnv *env, jclass clazz);
    JNIEXPORT jlong JNICALL FN(nMake)(JNIEnv *env, jclass clazz, jobject app_context);
    JNIEXPORT jboolean JNICALL FN(nInitialize)(JNIEnv *env, jclass clazz, jlong ptr);
    JNIEXPORT jboolean JNICALL FN(nSetEventListener)(JNIEnv *env, jclass clazz, jlong ptr, jobject listener);
    
    /**
     * 执行 mpv 命令
     */
    JNIEXPORT jboolean JNICALL FN(nCommand)(JNIEnv *env, jclass clazz, jlong ptr, jobjectArray args);
    /**
     * 设置 mpv 选项
     */
    JNIEXPORT jboolean JNICALL FN(nOption)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jstring value);
    
    // 设置播放器属性
    JNIEXPORT jint JNICALL FN(nGetPropertyInt)(JNIEnv *env, jclass clazz, jlong ptr, jstring key);
    JNIEXPORT jdouble JNICALL FN(nGetPropertyDouble)(JNIEnv *env, jclass clazz, jlong ptr, jstring key);
    JNIEXPORT jboolean JNICALL FN(nGetPropertyBoolean)(JNIEnv *env, jclass clazz, jlong ptr, jstring key);
    JNIEXPORT jstring JNICALL FN(nGetPropertyString)(JNIEnv *env, jclass clazz, jlong ptr, jstring key);
    
    JNIEXPORT jboolean JNICALL FN(nSetPropertyString)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jstring value);
    JNIEXPORT jboolean JNICALL FN(nSetPropertyInt)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jint value);
    JNIEXPORT jboolean JNICALL FN(nSetPropertyDouble)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jdouble value);
    JNIEXPORT jboolean JNICALL FN(nSetPropertyBoolean)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jboolean value);
    
    JNIEXPORT jboolean JNICALL FN(nObserveProperty)(JNIEnv *env, jclass clazz, jlong ptr, jstring name, jint format, jlong reply_data);
    JNIEXPORT jboolean JNICALL FN(nUnobserveProperty)(JNIEnv *env, jclass clazz, jlong ptr, jlong reply_data);
    
    // renderer
    JNIEXPORT jboolean JNICALL FN_ANDROID(nAttachAndroidSurface)(JNIEnv *env, jclass clazz, jlong ptr, jobject surface);
    JNIEXPORT jboolean JNICALL FN_ANDROID(nDetachAndroidSurface)(JNIEnv *env, jclass clazz, jlong ptr);
    
/**
 * 关闭此 mpv_handle_t 实例
 */
    JNIEXPORT jboolean JNICALL FN(nDestroy)(JNIEnv *env, jclass clazz, jlong ptr);
    /**
     * 被 GC 调用，回收 mpv_handle_t
     */
    JNIEXPORT void JNICALL FN(nFinalize)(JNIEnv *env, jclass clazz, jlong ptr);
}

// implementations

JNIEXPORT jboolean JNICALL FN(nGlobalInit)(JNIEnv *env, jclass clazz) {
    return true;
}

JNIEXPORT jlong JNICALL FN(nMake)(JNIEnv *env, jclass clazz, jobject app_context) {
    auto* handle = new mediampv::mpv_handle_t(env, app_context);
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jboolean JNICALL FN(nInitialize)(JNIEnv *env, jclass clazz, jlong ptr) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    return instance->initialize();
}

JNIEXPORT jboolean JNICALL FN(nSetEventListener)
        (JNIEnv *env, jclass clazz, jlong ptr, jobject listener) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    return instance->set_event_listener(env, listener);
}

JNIEXPORT jboolean JNICALL FN(nCommand)(JNIEnv *env, jclass clazz, jlong ptr, jobjectArray args) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));

    const char *arguments[128] = { 0 };
    jsize len = env->GetArrayLength(args);

    if (len >= sizeof(arguments) / sizeof(arguments[0])) {
        LOG("arguments are too long (>128)");
        return false;
    }

    for (int i = 0; i < len; ++i)
        arguments[i] = env->GetStringUTFChars((jstring)env->GetObjectArrayElement(args, i), nullptr);

    bool result = instance->command(arguments);

    for (int i = 0; i < len; ++i)
        env->ReleaseStringUTFChars((jstring)env->GetObjectArrayElement(args, i), arguments[i]);
    
    return result;
}

JNIEXPORT jboolean JNICALL FN(nOption)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jstring value) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));

    const char *option_key_char = env->GetStringUTFChars(key, nullptr);
    const char *option_value_char = env->GetStringUTFChars(value, nullptr);
    
    bool result = instance->set_option(option_key_char, option_value_char);

    env->ReleaseStringUTFChars(key, option_key_char);
    env->ReleaseStringUTFChars(value, option_value_char);
    
    return result;
}

// property set and get

JNIEXPORT jint JNICALL FN(nGetPropertyInt)(JNIEnv *env, jclass clazz, jlong ptr, jstring key) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    
    const char *key_char = env->GetStringUTFChars(key, nullptr);
    int result = 0;
    
    instance->get_property(key_char, MPV_FORMAT_INT64, &result);
    env->ReleaseStringUTFChars(key, key_char);
    
    return result;
}

JNIEXPORT jdouble JNICALL FN(nGetPropertyDouble)(JNIEnv *env, jclass clazz, jlong ptr, jstring key) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    
    const char *key_char = env->GetStringUTFChars(key, nullptr);
    double result = 0;
    
    instance->get_property(key_char, MPV_FORMAT_DOUBLE, &result);
    env->ReleaseStringUTFChars(key, key_char);
    
    return result;
}

JNIEXPORT jboolean JNICALL FN(nGetPropertyBoolean)(JNIEnv *env, jclass clazz, jlong ptr, jstring key) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    
    const char *key_char = env->GetStringUTFChars(key, nullptr);
    int result = 0;
    
    instance->get_property(key_char, MPV_FORMAT_FLAG, &result);
    env->ReleaseStringUTFChars(key, key_char);
    
    return result;
}

JNIEXPORT jstring JNICALL FN(nGetPropertyString)(JNIEnv *env, jclass clazz, jlong ptr, jstring key) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    
    const char *key_char = env->GetStringUTFChars(key, nullptr);
    char *result = nullptr;
    
    instance->get_property(key_char, MPV_FORMAT_STRING, &result);
    env->ReleaseStringUTFChars(key, key_char);
    
    jstring jresult = env->NewStringUTF(result);
    mpv_free(result); // TODO: move to mpv_handle_t
    
    return jresult;
}

JNIEXPORT jboolean JNICALL FN(nSetPropertyString)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jstring value) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    
    const char *key_char = env->GetStringUTFChars(key, nullptr);
    const char *value_char = env->GetStringUTFChars(value, nullptr);

    bool result = instance->set_property(key_char, MPV_FORMAT_STRING, &value_char);
    
    env->ReleaseStringUTFChars(key, key_char);
    env->ReleaseStringUTFChars(value, value_char);
    
    return result;
}

JNIEXPORT jboolean JNICALL FN(nSetPropertyInt)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jint value) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    
    const char *key_char = env->GetStringUTFChars(key, nullptr);
    
    bool result = instance->set_property(key_char, MPV_FORMAT_INT64, &value);
    env->ReleaseStringUTFChars(key, key_char);
    
    return result;
}

JNIEXPORT jboolean JNICALL FN(nSetPropertyDouble)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jdouble value) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    
    const char *key_char = env->GetStringUTFChars(key, nullptr);

    bool result = instance->set_property(key_char, MPV_FORMAT_DOUBLE, &value);
    env->ReleaseStringUTFChars(key, key_char);
    
    return result;
}

JNIEXPORT jboolean JNICALL FN(nSetPropertyBoolean)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jboolean value) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    
    const char *key_char = env->GetStringUTFChars(key, nullptr);

    bool result = instance->set_property(key_char, MPV_FORMAT_FLAG, &value);
    env->ReleaseStringUTFChars(key, key_char);
    
    return result;
}

JNIEXPORT jboolean JNICALL FN(nObserveProperty)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jint format, jlong reply_data) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    
    const char *key_char = env->GetStringUTFChars(key, nullptr);
    
    bool result = instance->observe_property(key_char, static_cast<mpv_format>(format), reply_data);
    env->ReleaseStringUTFChars(key, key_char);
    
    return result;
}

JNIEXPORT jboolean JNICALL FN(nUnobserveProperty)(JNIEnv *env, jclass clazz, jlong ptr, jlong reply_data) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    return instance->unobserve_property(reply_data);
}

JNIEXPORT jboolean JNICALL FN_ANDROID(nAttachAndroidSurface)(JNIEnv *env, jclass clazz, jlong ptr, jobject surface) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    return instance->attach_android_surface(env, surface);
}

JNIEXPORT jboolean JNICALL FN_ANDROID(nDetachAndroidSurface)(JNIEnv *env, jclass clazz, jlong ptr) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    return instance->detach_android_surface(env);
}

JNIEXPORT jboolean JNICALL FN(nDestroy)(JNIEnv *env, jclass clazz, jlong ptr) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    return instance->destroy(env);
}

JNIEXPORT void JNICALL FN(nFinalize)(JNIEnv *env, jclass clazz, jlong ptr) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    delete instance;
}