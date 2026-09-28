#include "log.h"

#include <cstdarg>
#include <cstdint>
#include <condition_variable>
#include <cstdio>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <jni.h>

#include "method_cache.h"
#include "handle_registry.h"

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace mediampv {

namespace {

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

struct log_line {
    jlong handle;
    int level;
    std::string prefix;
    std::string text;
};

// Delivers native log lines to the Kotlin sink from one dedicated thread. Callers only
// format and enqueue, so logging never calls into the JVM on the logging thread: a
// log statement made while holding a native lock (render_mutex_, handle_lock_, ...)
// cannot deadlock against, or be re-entered by, whatever the Kotlin handler does.
// FIFO order is preserved. Leaked on purpose (like its detached thread), so process
// exit never destroys the queue under the running thread.
class log_pump final {
public:
    void post(log_line line) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (queue_.size() >= kMaxQueuedLines) {
                ++dropped_;
                return;
            }
            queue_.push_back(std::move(line));
            if (!started_) {
                started_ = true;
                std::thread([this] { run(); }).detach();
            }
        }
        cv_.notify_one();
    }

private:
    // Bounds memory if the Kotlin handler stalls; mpv at msg-level=v is chatty.
    static constexpr size_t kMaxQueuedLines = 8192;

    void run() {
        JNIEnv *env = nullptr;
        std::deque<log_line> batch;
        for (;;) {
            size_t dropped = 0;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                cv_.wait(lock, [this] { return !queue_.empty() || dropped_ != 0; });
                batch.swap(queue_);
                std::swap(dropped, dropped_);
            }
            if (!env) env = attach_daemon();
            for (const log_line &line : batch) deliver(env, line.handle, line.level, line.prefix, line.text);
            batch.clear();
            if (dropped != 0) {
                const std::string text = std::to_string(dropped) + " log lines dropped: the log handler is too slow";
                deliver(env, 0, LOG_LEVEL_WARN, "mediampv", text);
            }
        }
    }

    // Attached for the thread's lifetime as a daemon, so it never keeps the JVM alive.
    static JNIEnv *attach_daemon() {
        JavaVM *vm = global_jvm;
        if (!vm) return nullptr;
        JNIEnv *env = nullptr;
#if defined(__ANDROID__)
        if (vm->AttachCurrentThreadAsDaemon(&env, nullptr) != JNI_OK) return nullptr;
#else
        if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return nullptr;
#endif
        return env;
    }

    static void deliver(JNIEnv *env, jlong handle, int level, const std::string &prefix, const std::string &text) {
        if (!env || !jni_mediamp_clazz_MPVLogKt || !jni_mediamp_method_MPVLogKt_onNativeLog) {
            log_to_stderr(level, prefix.c_str(), text.c_str());
            return;
        }
        jstring jprefix = env->NewStringUTF(prefix.c_str());
        jstring jtext = env->NewStringUTF(text.c_str());
        if (jprefix && jtext) {
            env->CallStaticVoidMethod(jni_mediamp_clazz_MPVLogKt,
                                      jni_mediamp_method_MPVLogKt_onNativeLog,
                                      handle, static_cast<jint>(level), jprefix, jtext);
        } else {
            log_to_stderr(level, prefix.c_str(), text.c_str());
        }
        // Never let a failure of the logging path itself linger on this thread.
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (jprefix) env->DeleteLocalRef(jprefix);
        if (jtext) env->DeleteLocalRef(jtext);
    }

    std::mutex mutex_;
    std::condition_variable cv_;
    std::deque<log_line> queue_;
    size_t dropped_ = 0;
    bool started_ = false;
};

log_pump &pump() {
    static log_pump *const instance = new log_pump();
    return *instance;
}

// noexcept: logging runs in error paths and exception handlers, so it must never throw.
void dispatch(const void *instance_handle, int level, const char *prefix, const char *text) noexcept {
    if (!prefix) prefix = "mediampv";
    if (!text) text = "";
    if (!global_jvm) {
        // No JVM yet (before the first player exists): nothing could ever deliver it.
        log_to_stderr(level, prefix, text);
        return;
    }
    try {
        // Resolve the id now, while the instance is certainly alive.
        pump().post(log_line{log_id_for(instance_handle), level, prefix, text});
    } catch (...) {
        // Out of memory, or the pump thread could not start.
        log_to_stderr(level, prefix, text);
    }
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
