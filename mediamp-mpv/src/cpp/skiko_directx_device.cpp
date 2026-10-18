/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#ifdef _WIN32

// Match the MSVC COM ABI when GetAdapterLuid returns a struct under MinGW.
#define WIDL_EXPLICIT_AGGREGATE_RETURNS
#include <windows.h>
#include <d3d12.h>
#include <dxgi1_4.h>

#include <cstring>

#include "skiko_directx_device.h"
#include "log.h"

namespace mediampv {
namespace {

bool has_readable_vtable(const void *object) {
    if (!object) return false;
    if (reinterpret_cast<uintptr_t>(object) & (sizeof(void *) - 1)) return false;
    if (IsBadReadPtr(object, sizeof(void *))) return false;
    void *vtable = nullptr;
    std::memcpy(&vtable, object, sizeof(vtable));
    if (!vtable) return false;
    if (reinterpret_cast<uintptr_t>(vtable) & (sizeof(void *) - 1)) return false;
    return !IsBadReadPtr(vtable, sizeof(void *));
}

} // namespace

// Both known 64-bit DirectXDevice layouts start with these pointer-sized slots:
//   slot 0: HWND hWnd
//   slot 1: IDXGIAdapter1* (0.9.37 backendContext.fAdapter; 0.153.0 adapter)
//   slot 2: ID3D12Device* (0.9.37 backendContext.fDevice; 0.153.0 device)
// Sources: JetBrains/skiko v0.9.37 awtMain/cpp/windows/directXRedrawer.cc and
// v0.153.0 awtMain/cpp/windows/directXRenderer.cc (under skiko/src).
//
// Skiko 0.153 removed backendContext: its queue is slot 4, and slots 6/8 are now
// buffers[1]/dcDevice, not duplicate device/queue pointers. Only read the shared
// prefix; verify COM types and the adapter/device LUID instead of those duplicates.
// This is still a private ABI. IsBadReadPtr is only a best-effort guard against
// unmapped memory, not a guarantee against stale or mapped-but-invalid pointers.
ID3D12Device *open_skia_d3d12_device(const void *instance_handle, int64_t skiko_device_ptr) {
    if (skiko_device_ptr == 0) return nullptr;
    if (skiko_device_ptr & static_cast<int64_t>(sizeof(void *) - 1)) {
        LOG(instance_handle, LOG_LEVEL_ERROR,
            "Skiko device pointer %lld is misaligned; not a DirectXDevice",
            static_cast<long long>(skiko_device_ptr));
        return nullptr;
    }

    const void *address = reinterpret_cast<const void *>(static_cast<uintptr_t>(skiko_device_ptr));
    void *prefix[3] = {};
    if (IsBadReadPtr(address, sizeof(prefix))) {
        LOG(instance_handle, LOG_LEVEL_ERROR,
            "Skiko device pointer span is not readable; not a DirectXDevice");
        return nullptr;
    }
    std::memcpy(prefix, address, sizeof(prefix));
    void *adapter_candidate = prefix[1];
    void *device_candidate = prefix[2];
    if (!has_readable_vtable(adapter_candidate) || !has_readable_vtable(device_candidate)) {
        LOG(instance_handle, LOG_LEVEL_ERROR,
            "Skiko adapter/device candidate has no readable vtable; not a COM object");
        return nullptr;
    }

    ID3D12Device *device = nullptr;
    HRESULT hr = static_cast<IUnknown *>(device_candidate)->QueryInterface(
        __uuidof(ID3D12Device), reinterpret_cast<void **>(&device));
    if (FAILED(hr) || !device) {
        LOG(instance_handle, LOG_LEVEL_ERROR,
            "Skiko DirectXDevice candidate is not an ID3D12Device (hr=0x%lx)", hr);
        return nullptr;
    }

    IDXGIAdapter1 *adapter = nullptr;
    hr = static_cast<IUnknown *>(adapter_candidate)->QueryInterface(
        __uuidof(IDXGIAdapter1), reinterpret_cast<void **>(&adapter));
    if (FAILED(hr) || !adapter) {
        LOG(instance_handle, LOG_LEVEL_ERROR,
            "Skiko DirectXDevice candidate is not an IDXGIAdapter1 (hr=0x%lx)", hr);
        device->Release();
        return nullptr;
    }

    DXGI_ADAPTER_DESC1 desc{};
    hr = adapter->GetDesc1(&desc);
    adapter->Release();
    if (FAILED(hr)) {
        LOG(instance_handle, LOG_LEVEL_ERROR,
            "Skiko DirectXDevice adapter GetDesc1 failed (hr=0x%lx)", hr);
        device->Release();
        return nullptr;
    }

    LUID device_luid{};
    device->GetAdapterLuid(&device_luid);
    if (desc.AdapterLuid.LowPart != device_luid.LowPart ||
        desc.AdapterLuid.HighPart != device_luid.HighPart) {
        LOG(instance_handle, LOG_LEVEL_ERROR,
            "Skiko DirectXDevice adapter/device check failed: "
            "adapter LUID %08lx:%08lx != device LUID %08lx:%08lx",
            static_cast<unsigned long>(desc.AdapterLuid.HighPart),
            static_cast<unsigned long>(desc.AdapterLuid.LowPart),
            static_cast<unsigned long>(device_luid.HighPart),
            static_cast<unsigned long>(device_luid.LowPart));
        device->Release();
        return nullptr;
    }
    return device; // AddRef'd by QueryInterface; caller owns.
}

} // namespace mediampv

#endif // _WIN32
