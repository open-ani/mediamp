#include <iostream>
#include <jni.h>
#include <cstdio>
#include "method_cache.h"

#define FN(name) Java_org_openani_mediamp_backend_mpv_MPVHandleKt_##name

extern "C" {
    JNIEXPORT jboolean JNICALL FN(nGlobalInit)(JNIEnv *env, jclass clazz, jobject obj);
    JNIEXPORT jboolean JNICALL FN(nMake)(JNIEnv *env, jclass clazz, jobject obj);
}

JNIEXPORT jboolean JNICALL FN(nGlobalInit)(JNIEnv *env, jclass clazz, jobject obj) {
    
    return true;
}

JNIEXPORT jboolean JNICALL FN(nMake)(JNIEnv *env, jclass clazz, jobject obj) {

    return true;
}