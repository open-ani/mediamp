#include "log.h"

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <jni.h>

#include "method_cache.h"

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace mediampv {

namespace {

// Attaches the calling thread to the JVM when needed and detaches on scope exit only if
// this helper performed the attach, so a thread the JVM already owns (event loop, render
// thread, a thread inside a JNI downcall) is never wrongly detached.
struct scoped_env final {
    explicit scoped_env(JavaVM *vm) : vm_(vm) {
        if (!vm_) {
            return;
        }
        const jint rc = vm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        if (rc == JNI_OK) {
            return;
        }
        if (rc == JNI_EDETACHED) {
#if defined(__ANDROID__)
            if (vm_->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached_ = true;
            }
#else
            if (vm_->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) == JNI_OK) {
                attached_ = true;
            }
#endif
        }
    }

    ~scoped_env() {
        if (attached_ && vm_) {
            vm_->DetachCurrentThread();
        }
    }

    JNIEnv *env = nullptr;

private:
    JavaVM *vm_ = nullptr;
    bool attached_ = false;
};

// Last-resort sink used when the log line cannot reach the Kotlin handler. Never silently
// drops the line: startup errors (before the JVM/cache exist) and JNI-path failures still
// surface somewhere a developer can see them.
void log_to_stderr(int level, const char *prefix, const char *text) {
    if (!prefix) prefix = "mediampv";
    if (!text) text = "";
#if defined(__ANDROID__)
    int priority;
    if (level <= LOG_LEVEL_FATAL) priority = ANDROID_LOG_FATAL;
    else if (level <= LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level <= LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    else if (level <= LOG_LEVEL_INFO) priority = ANDROID_LOG_INFO;
    else if (level <= LOG_LEVEL_DEBUG) priority = ANDROID_LOG_DEBUG;
    else priority = ANDROID_LOG_VERBOSE;
    __android_log_print(priority, prefix, "%s", text);
#else
    // stderr is block-buffered when piped (e.g. under Gradle); flush per line so logs are
    // not swallowed until the buffer fills or the process exits.
    fprintf(stderr, "[%s] %s\n", prefix, text);
    fflush(stderr);
#endif
}

void dispatch(const void *instance_handle, int level, const char *prefix, const char *text) {
    if (!prefix) prefix = "mediampv";
    if (!text) text = "";

    JavaVM *vm = global_jvm;
    if (!vm || !jni_mediamp_clazz_MPVLogKt || !jni_mediamp_method_MPVLogKt_onNativeLog) {
        // JVM not attached yet, or the log method has not been cached: don't lose the line.
        log_to_stderr(level, prefix, text);
        return;
    }

    scoped_env scoped(vm);
    JNIEnv *env = scoped.env;
    if (!env) {
        log_to_stderr(level, prefix, text);
        return;
    }

    // Any JNI call made while an exception is pending is undefined behaviour. This path is
    // reached from clear_jni_exception() and from error handlers that run right after a
    // failed JNI call, so fall back to stderr and leave the pending exception untouched for
    // the caller to describe/clear.
    if (env->ExceptionCheck()) {
        log_to_stderr(level, prefix, text);
        return;
    }

    jstring jprefix = env->NewStringUTF(prefix);
    jstring jtext = env->NewStringUTF(text);
    if (jprefix && jtext) {
        env->CallStaticVoidMethod(jni_mediamp_clazz_MPVLogKt,
                                  jni_mediamp_method_MPVLogKt_onNativeLog,
                                  static_cast<jlong>(reinterpret_cast<std::uintptr_t>(instance_handle)),
                                  static_cast<jint>(level), jprefix, jtext);
    } else {
        log_to_stderr(level, prefix, text);
    }

    // Never propagate a failure of the logging path itself back to the caller.
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (jprefix) env->DeleteLocalRef(jprefix);
    if (jtext) env->DeleteLocalRef(jtext);
}

void log_vprint(const void *instance_handle, int level, const char *format, va_list args) {
    char buffer[2048];
    const int written = vsnprintf(buffer, sizeof(buffer), format ? format : "", args);
    if (written < 0) {
        return;
    }
    dispatch(instance_handle, level, "mediampv", buffer);
}

} // namespace

void log_print(int level, const char *format, ...) {
    va_list args;
    va_start(args, format);
    log_vprint(nullptr, level, format, args);
    va_end(args);
}

void log_print(const void *instance_handle, int level, const char *format, ...) {
    va_list args;
    va_start(args, format);
    log_vprint(instance_handle, level, format, args);
    va_end(args);
}

void log_forward(int level, const char *prefix, const char *text) {
    dispatch(nullptr, level, prefix, text);
}

void log_forward(const void *instance_handle, int level, const char *prefix, const char *text) {
    dispatch(instance_handle, level, prefix, text);
}

} // namespace mediampv
