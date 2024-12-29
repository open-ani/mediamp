//
// Created by StageGuard on 12/28/2024.
//

#ifndef MEDIAMP_LOG_H
#define MEDIAMP_LOG_H

#ifdef ENABLE_LOGGING
#define LOG_TAG "mediampv"
#if (defined(__APPLE__) && defined(__MACH__)) || (defined(__linux__) && !defined(__ANDROID__))
#include <android/log.h>
#define LOG(...) (__android_log_print(ANDROID_LOG_TRACE, LOG_TAG, __VA_ARGS__))
#else
#include <cstdio>
#define LOG(...) (printf(__VA_ARGS__))
#endif // defined(__APPLE__) && defined(__MACH__) || (defined(__linux__) && !defined(__ANDROID__))
#else
#define LOG(...) ((void *) 0)
#endif // ENABLE_LOGGING

#endif //MEDIAMP_LOG_H
