#pragma once

#ifndef MEDIAMP_METHOD_CACHE_H
#define MEDIAMP_METHOD_CACHE_H

#include <jni.h>

namespace mediampv {

#ifndef UTIL_EXTERN
#define UTIL_EXTERN extern
#endif

// The process-wide JavaVM, captured in mpv_handle_t::create(). Null until the first handle
// is created; the log dispatcher reads it and falls back to stderr while it is null. Plain
// `extern` (not UTIL_EXTERN): the single definition lives in mpv_handle_t.cpp.
extern JavaVM *global_jvm;

UTIL_EXTERN jclass jni_mediamp_clazz_EventListener;
UTIL_EXTERN jmethodID jni_mediamp_method_EventListener_onPropertyChange_NONE;
UTIL_EXTERN jmethodID jni_mediamp_method_EventListener_onPropertyChange_FLAG;
UTIL_EXTERN jmethodID jni_mediamp_method_EventListener_onPropertyChange_INT64;
UTIL_EXTERN jmethodID jni_mediamp_method_EventListener_onPropertyChange_DOUBLE;
UTIL_EXTERN jmethodID jni_mediamp_method_EventListener_onPropertyChange_STRING;
UTIL_EXTERN jmethodID jni_mediamp_method_EventListener_onEvent;
UTIL_EXTERN jmethodID jni_mediamp_method_EventListener_onEndFile;
UTIL_EXTERN jclass jni_mediamp_clazz_RenderUpdateListener;
UTIL_EXTERN jmethodID jni_mediamp_method_RenderUpdateListener_onRenderUpdate;
UTIL_EXTERN jclass jni_mediamp_clazz_MPVLogKt;
UTIL_EXTERN jmethodID jni_mediamp_method_MPVLogKt_onNativeLog;
UTIL_EXTERN jclass jni_mediamp_clazz_SeekableInput;
UTIL_EXTERN jmethodID jni_mediamp_method_SeekableInput_read;
UTIL_EXTERN jmethodID jni_mediamp_method_SeekableInput_seekTo;
UTIL_EXTERN jmethodID jni_mediamp_method_SeekableInput_close;
#ifdef __ANDROID__
UTIL_EXTERN jclass jni_mediamp_clazz_android_Surface;
#endif

// Resolves and caches every JNI class/method the native<->Kotlin bridge dispatches to.
// All-or-nothing: returns true when the cache is populated (possibly by an earlier call),
// false when any class or method failed to resolve — e.g. the Kotlin mediamp-mpv artifact
// on the classpath does not match the version this native library was built against.
bool jni_cache_classes(JNIEnv *env, const void *instance_handle = nullptr);

// Raises a Java exception of `class_name` (e.g. "java/lang/IllegalStateException") carrying
// `message`. The native method must return promptly afterwards; the exception is delivered
// when control returns to the JVM. No-op when `env` is null or an exception is already
// pending, so an in-flight failure (e.g. OutOfMemoryError from a failed JNI allocation) is
// never clobbered by a less specific one.
void throw_java_exception(
        JNIEnv *env,
        const char *class_name,
        const char *message,
        const void *instance_handle = nullptr);
void throw_illegal_state(JNIEnv *env, const char *message, const void *instance_handle = nullptr);
void throw_illegal_argument(JNIEnv *env, const char *message, const void *instance_handle = nullptr);

} // namespace mediampv

#endif //MEDIAMP_METHOD_CACHE_H
