#include <stdarg.h>
#include "libavutil/log.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*ffmpegkit_log_callback_fn)(int level, const char *message);

int main(int argc, char **argv);
int ffmpegkit_execute(int argc, char **argv);
void ffmpegkit_set_log_callback(ffmpegkit_log_callback_fn callback);
void ffmpegkit_forward_log(int level, const char *message);

static ffmpegkit_log_callback_fn g_log_callback = 0;

#if defined(_MSC_VER)
#define THREAD_LOCAL __declspec(thread)
#else
#define THREAD_LOCAL __thread
#endif

static THREAD_LOCAL int g_log_print_prefix = 1;

__attribute__((visibility("default")))
void ffmpegkit_forward_log(int level, const char *message) {
    if (g_log_callback != 0 && message != 0 && message[0] != '\0') {
        g_log_callback(level, message);
    }
}

static void ffmpegkit_av_log_callback(void *ptr, int level, const char *fmt, va_list vl) {
    char line[4096];
    int print_prefix = g_log_print_prefix;
    av_log_format_line2(ptr, level, fmt, vl, line, (int)sizeof(line), &print_prefix);
    g_log_print_prefix = print_prefix;
    ffmpegkit_forward_log(level, line);
}

__attribute__((visibility("default")))
void ffmpegkit_set_log_callback(ffmpegkit_log_callback_fn callback) {
    g_log_callback = callback;
}

__attribute__((visibility("default")))
int ffmpegkit_execute(int argc, char **argv) {
    if (g_log_callback != 0) {
        av_log_set_callback(ffmpegkit_av_log_callback);
    }
    return main(argc, argv);
}

#ifdef __cplusplus
}
#endif
