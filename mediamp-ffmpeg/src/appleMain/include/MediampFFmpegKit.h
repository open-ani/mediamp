/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#ifndef MEDIAMP_FFMPEGKIT_H
#define MEDIAMP_FFMPEGKIT_H

#if defined(__cplusplus)
extern "C" {
#endif

typedef void (*ffmpegkit_log_callback_fn)(int level, const char *message);

int ffmpegkit_execute(int argc, char **argv);
void ffmpegkit_set_log_callback(ffmpegkit_log_callback_fn callback);

#if defined(__cplusplus)
}
#endif

#endif
