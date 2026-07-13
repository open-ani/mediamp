#pragma once

#ifndef MEDIAMP_LOG_H
#define MEDIAMP_LOG_H

namespace mediampv {

// Log levels mirror mpv's mpv_log_level scale (client.h): lower value == more severe.
// mediamp's own native logs, mpv's MPV_EVENT_LOG_MESSAGE lines, and the Kotlin logs all
// share this one scale so a single MPVLogHandler on the Kotlin side can filter uniformly.
enum log_level {
    LOG_LEVEL_FATAL = 10, // critical, aborting errors
    LOG_LEVEL_ERROR = 20, // simple errors
    LOG_LEVEL_WARN = 30,  // possible problems
    LOG_LEVEL_INFO = 40,  // informational messages
    LOG_LEVEL_V = 50,     // noisy informational messages
    LOG_LEVEL_DEBUG = 60, // very noisy technical detail
    LOG_LEVEL_TRACE = 70, // extremely noisy
};

// Formats a mediamp native log line (prefix "mediampv") and dispatches it to the Kotlin
// MPVLog sink via JNI (MPVLogKt.onNativeLog). Safe to call from any thread and at any
// time: it attaches the calling thread if needed and falls back to stderr when the JVM /
// JNI cache is not ready, when a JNI exception is already pending, or when the dispatch
// itself fails. It never throws and never leaves a JNI exception pending.
void log_print(int level, const char *format, ...)
#if defined(__GNUC__) || defined(__clang__)
        __attribute__((format(printf, 2, 3)))
#endif
        ;

// Instance-aware overload. instance_handle is the address of the owning mpv_handle_t;
// Kotlin receives the same address as onNativeLog(instanceHandle, ...). A null handle is
// reported as 0L and is reserved for process-wide logs that do not belong to one instance.
void log_print(const void *instance_handle, int level, const char *format, ...)
#if defined(__GNUC__) || defined(__clang__)
        __attribute__((format(printf, 3, 4)))
#endif
        ;

// Forwards an already-formatted line that carries its own prefix (used for mpv's own log
// messages from MPV_EVENT_LOG_MESSAGE). Same dispatch and fallback behaviour as log_print.
void log_forward(int level, const char *prefix, const char *text);
void log_forward(const void *instance_handle, int level, const char *prefix, const char *text);

} // namespace mediampv

// Canonical forms, selected by the overloaded log_print function:
//   LOG(level, fmt, ...)
//   LOG(instanceHandle, level, fmt, ...)
// instanceHandle must be the owning mpv_handle_t pointer (or another const void *).
#define LOG(...) ::mediampv::log_print(__VA_ARGS__)

// Level-tagged conveniences (preferred at call sites; each encodes the level in its name).
#define LOGF(...) ::mediampv::log_print(::mediampv::LOG_LEVEL_FATAL, __VA_ARGS__)
#define LOGE(...) ::mediampv::log_print(::mediampv::LOG_LEVEL_ERROR, __VA_ARGS__)
#define LOGW(...) ::mediampv::log_print(::mediampv::LOG_LEVEL_WARN, __VA_ARGS__)
#define LOGI(...) ::mediampv::log_print(::mediampv::LOG_LEVEL_INFO, __VA_ARGS__)
#define LOGV(...) ::mediampv::log_print(::mediampv::LOG_LEVEL_V, __VA_ARGS__)
#define LOGD(...) ::mediampv::log_print(::mediampv::LOG_LEVEL_DEBUG, __VA_ARGS__)
#define LOGT(...) ::mediampv::log_print(::mediampv::LOG_LEVEL_TRACE, __VA_ARGS__)

#endif // MEDIAMP_LOG_H
