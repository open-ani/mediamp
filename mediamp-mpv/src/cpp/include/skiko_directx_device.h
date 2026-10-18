/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#ifndef MEDIAMP_SKIKO_DIRECTX_DEVICE_H
#define MEDIAMP_SKIKO_DIRECTX_DEVICE_H

#ifdef _WIN32
#include <cstdint>

struct ID3D12Device;

namespace mediampv {

// Borrows Skiko's live DirectXDevice handle; returns an AddRef'd D3D12 device or null.
ID3D12Device *open_skia_d3d12_device(const void *instance_handle, int64_t skiko_device_ptr);

} // namespace mediampv
#endif

#endif // MEDIAMP_SKIKO_DIRECTX_DEVICE_H
