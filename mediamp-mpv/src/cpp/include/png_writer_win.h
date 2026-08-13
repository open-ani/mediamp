/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#ifndef MEDIAMP_PNG_WRITER_WIN_H
#define MEDIAMP_PNG_WRITER_WIN_H

#ifdef _WIN32

#include <cstdint>
#include <vector>

namespace mediampv {

/**
 * Encodes ARGB_8888 pixels (0xAARRGGBB ints, row-major, top-down — i.e. 32bppBGRA
 * bytes in little-endian memory) as a PNG file through WIC. Initializes COM on the
 * calling thread if needed. Shared by the D3D11 and OpenGL-fallback render paths'
 * save_surface_png, which cannot use mpv's own screenshot pipeline (it cannot convert
 * hwdec frames without zimg).
 */
bool write_argb_png_wic(const char *path, int width, int height,
                        const std::vector<uint32_t> &pixels);

} // namespace mediampv

#endif // _WIN32

#endif // MEDIAMP_PNG_WRITER_WIN_H
