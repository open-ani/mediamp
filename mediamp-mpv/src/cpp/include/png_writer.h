/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#ifndef MEDIAMP_PNG_WRITER_H
#define MEDIAMP_PNG_WRITER_H

// Used by the Windows (D3D11 + OpenGL) and Linux render paths for screenshot readback.
// macOS encodes through ImageIO in render_macos.mm instead.
#if defined(_WIN32) || (defined(__linux__) && !defined(__ANDROID__))

#include <cstdint>

namespace mediampv {

/**
 * Writes [width] x [height] ARGB_8888 pixels (`0xAARRGGBB`, row-major, top-down — the
 * layout the render paths' `read_surface_pixels` produces) to [path] as an opaque PNG.
 * Alpha is written as 255 regardless of the input: mpv leaves it undefined for opaque
 * video.
 *
 * Encoded with WIC on Windows and zlib elsewhere, so neither platform needs an image
 * library beyond what the JNI wrapper already links.
 */
bool write_argb_png(const char *path, int width, int height, const uint32_t *pixels);

} // namespace mediampv

#endif // _WIN32 || desktop Linux

#endif // MEDIAMP_PNG_WRITER_H
