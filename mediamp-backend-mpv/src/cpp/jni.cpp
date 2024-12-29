#include <iostream>
#include <jni.h>
#include <cstdio>
#include "method_cache.h"
#include "mpv_handle_t.h"

#define FN(name) Java_org_openani_mediamp_backend_mpv_MPVHandleKt_##name

extern "C" {
    JNIEXPORT jboolean JNICALL FN(nGlobalInit)(JNIEnv *env, jclass clazz);
    JNIEXPORT jlong JNICALL FN(nMake)(JNIEnv *env, jclass clazz, jobject app_context);
    JNIEXPORT jboolean JNICALL FN(nInitialize)(JNIEnv *env, jclass clazz, jlong ptr);
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

JNIEXPORT jboolean JNICALL FN(nDestroy)(JNIEnv *env, jclass clazz, jlong ptr) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    return instance->destroy(env);
}

JNIEXPORT void JNICALL FN(nFinalize)(JNIEnv *env, jclass clazz, jlong ptr) {
    auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
    delete instance;
}