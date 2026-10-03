/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

// Windows render path: a dedicated render thread drives mpv (hwdec=d3d11va stays on
// GPU) through the libmpv D3D11 render API on our own ID3D11Device, into a ring of
// shared ID3D11Texture2D render targets. Each texture is also opened on the
// consumer-provided ID3D12Device (Skia's device, Compose's default Windows backend) as
// an ID3D12Resource via NT shared handles, so Compose/Skia can sample the video frames
// zero-copy. Mirrors the macOS path (render_macos.mm): IOSurface ring -> shared texture
// ring, CGL context -> D3D11 device, glFinish -> event-query wait.
//
// The ring protocol and threading model are shared with the other GPU paths
// (surface_ring.h). This path additionally supports CPU readback consumers (Skiko's
// software and ANGLE redrawers), which take frames from system memory instead of
// sampling ring textures.
//
// mpv leaves the alpha channel undefined for opaque video (see render_macos.mm). Its
// d3d11 renderer writes alpha=1 in practice, so the consumer wraps the texture as
// RGBA_8888 (Skia's D3D backend rejects the opaque RGB_888x for render targets), and the
// CPU readbacks (PNG/pixels) force alpha to 255. No native alpha-fix pass is needed.

#ifdef _WIN32

// COM methods returning a struct (ID3D12Device::GetAdapterLuid) use a hidden result
// pointer in the MSVC ABI; MinGW's default by-value declarations do not match it. The
// explicit form declares that pointer, so it is correct under both g++ and clang.
#define WIDL_EXPLICIT_AGGREGATE_RETURNS

#include <initguid.h>
#include <windows.h>
#include <d3d11_4.h>
#include <d3d12.h>
#include <dxgi1_4.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_d3d11.h>

#include "surface_ring.h"
#include "log.h"

namespace {

template <typename T>
void safe_release(T *&object) {
    if (object) {
        object->Release();
        object = nullptr;
    }
}

// Extracts the ID3D12Device from Skiko's native DirectXDevice struct (the value of the
// Direct3DRedrawer.device field, reflected on the Kotlin side).
//
// Expected x64 layout (skiko 0.9.37, awtMain/cpp/windows/directXRedrawer.cc):
//   slot 0 (byte  0): HWND hWnd
//   slot 2 (byte 16): backendContext.fDevice   (gr_cp<ID3D12Device>)
//   slot 3 (byte 24): backendContext.fQueue    (gr_cp<ID3D12CommandQueue>)
//   slot 6 (byte 48): device                   (gr_cp<ID3D12Device>)
//   slot 8 (byte 64): queue                    (gr_cp<ID3D12CommandQueue>)
//
// These offsets are inferred from member order, not an ABI guarantee, so we only trust
// them when two independent slots agree on the same pointer (fDevice == device and
// fQueue == queue — skiko stores the same COM pointers in both places), and even then
// the result must survive QueryInterface(ID3D12Device) before it is used.
ID3D12Device *open_skia_d3d12_device(const void *instance_handle, int64_t skiko_device_ptr) {
    if (skiko_device_ptr == 0) return nullptr;

    // Defense-in-depth around trusting a reflected pointer plus an inferred struct layout.
    // We build with MinGW g++, which has no MSVC __try/__except, so we validate reads with
    // IsBadReadPtr instead of catching an access violation. This cannot detect a
    // mapped-but-wrong region, so the two-slot agreement and the final QueryInterface stay
    // as the real validation — IsBadReadPtr only turns the most likely upgrade failure (a
    // shrunk/moved struct whose slots land on unmapped memory) into a graceful null.

    // A real DirectXDevice* is at least pointer-aligned; a misaligned value is not one.
    if (skiko_device_ptr & static_cast<int64_t>(sizeof(void *) - 1)) {
        LOG(instance_handle, mediampv::LOG_LEVEL_ERROR,
            "Skiko device pointer %lld is misaligned; not a DirectXDevice",
            static_cast<long long>(skiko_device_ptr));
        return nullptr;
    }
    auto *slots = reinterpret_cast<void *const *>(static_cast<uintptr_t>(skiko_device_ptr));
    // Need slots[0..8] readable (9 pointers).
    if (IsBadReadPtr(slots, 9 * sizeof(void *))) {
        LOG(instance_handle, mediampv::LOG_LEVEL_ERROR,
            "Skiko device pointer span is not readable; not a DirectXDevice");
        return nullptr;
    }

    void *device_a = slots[2], *device_b = slots[6];
    void *queue_a = slots[3], *queue_b = slots[8];
    if (!device_a || device_a != device_b || !queue_a || queue_a != queue_b) {
        LOG(instance_handle, mediampv::LOG_LEVEL_ERROR,
            "Skiko DirectXDevice layout check failed (fDevice=%p device=%p fQueue=%p queue=%p); "
            "video will not be wrapped for Skia",
            device_a, device_b, queue_a, queue_b);
        return nullptr;
    }

    // Before the virtual QueryInterface call, verify device_a has a readable vtable slot,
    // so a non-COM but coincidentally-agreeing pointer does not jump through garbage.
    if (IsBadReadPtr(device_a, sizeof(void *)) ||
        IsBadReadPtr(*reinterpret_cast<void *const *>(device_a), sizeof(void *))) {
        LOG(instance_handle, mediampv::LOG_LEVEL_ERROR,
            "Skiko device candidate has no readable vtable; not a COM object");
        return nullptr;
    }

    ID3D12Device *device = nullptr;
    HRESULT hr = static_cast<IUnknown *>(device_a)
                     ->QueryInterface(__uuidof(ID3D12Device), reinterpret_cast<void **>(&device));
    if (FAILED(hr) || !device) {
        LOG(instance_handle, mediampv::LOG_LEVEL_ERROR,
            "Skiko DirectXDevice candidate is not an ID3D12Device (hr=0x%lx)", hr);
        return nullptr;
    }
    return device;  // AddRef'd by QueryInterface; caller owns.
}

bool same_luid(const LUID &a, const LUID &b) {
    return a.LowPart == b.LowPart && a.HighPart == b.HighPart;
}

LUID d3d12_adapter_luid(ID3D12Device *device) {
    LUID luid{};
    device->GetAdapterLuid(&luid);
    return luid;
}

std::string adapter_name(const DXGI_ADAPTER_DESC &desc) {
    char name[256] = {};
    WideCharToMultiByte(CP_UTF8, 0, desc.Description, -1, name, sizeof(name) - 1, nullptr, nullptr);
    return name;
}

// The adapter an ID3D11Device was created on; false when DXGI cannot tell.
bool d3d11_adapter_desc(ID3D11Device *device, DXGI_ADAPTER_DESC &desc) {
    IDXGIDevice *dxgi_device = nullptr;
    if (FAILED(device->QueryInterface(__uuidof(IDXGIDevice), reinterpret_cast<void **>(&dxgi_device)))) {
        return false;
    }
    IDXGIAdapter *adapter = nullptr;
    HRESULT hr = dxgi_device->GetAdapter(&adapter);
    dxgi_device->Release();
    if (FAILED(hr) || !adapter) return false;
    hr = adapter->GetDesc(&desc);
    adapter->Release();
    return SUCCEEDED(hr);
}

// The DXGI adapter with `luid`; caller owns it.
IDXGIAdapter *adapter_by_luid(const void *instance_handle, const LUID &luid) {
    IDXGIFactory4 *factory = nullptr;
    HRESULT hr = CreateDXGIFactory1(__uuidof(IDXGIFactory4), reinterpret_cast<void **>(&factory));
    if (FAILED(hr) || !factory) {
        LOG(instance_handle, mediampv::LOG_LEVEL_WARN, "CreateDXGIFactory1(IDXGIFactory4) failed: 0x%lx", hr);
        return nullptr;
    }
    IDXGIAdapter *adapter = nullptr;
    hr = factory->EnumAdapterByLuid(luid, __uuidof(IDXGIAdapter), reinterpret_cast<void **>(&adapter));
    factory->Release();
    if (FAILED(hr) || !adapter) {
        LOG(instance_handle, mediampv::LOG_LEVEL_WARN,
            "EnumAdapterByLuid(%08lx:%08lx) failed: 0x%lx", luid.HighPart, luid.LowPart, hr);
        return nullptr;
    }
    return adapter;
}

// Adapter name for logs; "unknown" when DXGI cannot resolve the LUID.
std::string adapter_name_for_luid(const void *instance_handle, const LUID &luid) {
    IDXGIAdapter *adapter = adapter_by_luid(instance_handle, luid);
    if (!adapter) return "unknown";
    DXGI_ADAPTER_DESC desc{};
    const HRESULT hr = adapter->GetDesc(&desc);
    adapter->Release();
    return SUCCEEDED(hr) ? adapter_name(desc) : "unknown";
}

// The adapter Skia renders on, from Skiko's DirectXDevice pointer; caller owns it.
IDXGIAdapter *skia_adapter(const void *instance_handle, int64_t skiko_device_ptr) {
    ID3D12Device *skia_device = open_skia_d3d12_device(instance_handle, skiko_device_ptr);
    if (!skia_device) return nullptr;
    const LUID luid = d3d12_adapter_luid(skia_device);
    skia_device->Release();
    return adapter_by_luid(instance_handle, luid);
}

// VIDEO_SUPPORT is required for FFmpeg's d3d11va hwdevice_ctx (hwdec) to attach to the
// device; retried without it for drivers/WARP levels that reject the flag (playback then
// falls back to software decoding but rendering still works). With an explicit adapter
// the driver type must be UNKNOWN; without one, the default hardware adapter is tried
// before WARP (headless CI / no GPU).
HRESULT create_d3d11_device(
    const void *instance_handle, IDXGIAdapter *adapter,
    ID3D11Device **device, ID3D11DeviceContext **context) {
    const UINT flag_sets[] = {
        D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT,
        D3D11_CREATE_DEVICE_BGRA_SUPPORT,
    };
    const D3D_DRIVER_TYPE default_driver_types[] = {D3D_DRIVER_TYPE_HARDWARE, D3D_DRIVER_TYPE_WARP};
    const D3D_DRIVER_TYPE adapter_driver_types[] = {D3D_DRIVER_TYPE_UNKNOWN};
    const D3D_DRIVER_TYPE *driver_types = adapter ? adapter_driver_types : default_driver_types;
    const size_t driver_type_count = adapter ? ARRAYSIZE(adapter_driver_types) : ARRAYSIZE(default_driver_types);
    const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
    HRESULT hr = E_FAIL;
    for (size_t i = 0; i < driver_type_count; ++i) {
        const D3D_DRIVER_TYPE driver_type = driver_types[i];
        for (UINT flags : flag_sets) {
            hr = D3D11CreateDevice(
                adapter, driver_type, nullptr, flags,
                levels, ARRAYSIZE(levels), D3D11_SDK_VERSION, device, nullptr, context);
            if (hr == E_INVALIDARG) {
                // Pre-11.1 runtime rejects the 11_1 entry; retry without it.
                hr = D3D11CreateDevice(
                    adapter, driver_type, nullptr, flags,
                    levels + 1, ARRAYSIZE(levels) - 1, D3D11_SDK_VERSION, device, nullptr, context);
            }
            if (SUCCEEDED(hr)) return hr;
            if (!(flags & D3D11_CREATE_DEVICE_VIDEO_SUPPORT)) {
                LOG(instance_handle, mediampv::LOG_LEVEL_WARN,
                    "D3D11CreateDevice(adapter=%p type=%d flags=0x%x) failed (0x%lx)",
                    adapter, (int) driver_type, flags, hr);
            }
        }
    }
    return hr;
}

}  // namespace

namespace mediampv {

namespace {

struct d3d11_buffer {
    ID3D11Texture2D *texture = nullptr;        // render target on our device
    HANDLE shared_handle = nullptr;            // NT handle from CreateSharedHandle
    ID3D12Resource *d3d12_resource = nullptr;  // opened on the consumer's device, may be null
};

class d3d11_renderer final : public surface_ring<d3d11_buffer> {
public:
    explicit d3d11_renderer(mpv_handle_t &owner) : surface_ring(owner, "D3D11") {}
    ~d3d11_renderer() override { shutdown(); }

    bool create(int64_t consumer_device_hint);

    bool set_readback_surface_config(int width, int height) override {
        return request_config(width, height, 0, true);
    }
    uint64_t copy_latest_frame(void *dest, int width, int height) override;
    bool read_surface_pixels(std::vector<uint32_t> &pixels, int &width, int &height) override;

protected:
    bool prepare_device_locked(int64_t consumer_device, bool changed) override;
    void release_device_locked() override { safe_release(skia_device_); }
    bool allocate_buffer(d3d11_buffer &buffer, int width, int height) override;
    void destroy_buffer(d3d11_buffer &buffer) override;
    int64_t texture_handle(const d3d11_buffer &buffer) const override {
        return static_cast<int64_t>(reinterpret_cast<uintptr_t>(buffer.d3d12_resource));
    }
    bool render_into(const d3d11_buffer &buffer) override;
    bool render_frame_pixels_on_render_thread(int width, int height, std::vector<uint32_t> &pixels) override;
    bool setup_readback_locked() override;
    bool read_back(const d3d11_buffer &buffer) override;
    void publish_readback_locked() override { readback_scratch_.swap(readback_latest_); }
    void release_readback_locked() override;
    void after_shutdown() override;

private:
    bool wait_for_gpu();
    bool read_texture_argb(ID3D11Texture2D *source, std::vector<uint32_t> &pixels, int &width, int &height);

    // mpv renders on our own D3D11 device. Its immediate context is multithread-protected
    // so screenshot readbacks (JNI thread) can copy/map while the render thread is inside
    // mpv_render_context_render.
    ID3D11Device *device_ = nullptr;
    ID3D11DeviceContext *context_ = nullptr;
    ID3D11Query *flush_query_ = nullptr;  // D3D11_QUERY_EVENT, the glFinish equivalent
    // Consumer-side D3D12 device (owned reference), extracted from Skiko's native
    // DirectXDevice struct; null while the ring is headless (device 0).
    ID3D12Device *skia_device_ = nullptr;
    // CPU readback: the render thread owns the staging texture and readback_scratch_;
    // readback_latest_ is swapped in and copied out under mutex_.
    ID3D11Texture2D *readback_staging_ = nullptr;
    std::vector<uint8_t> readback_scratch_, readback_latest_;
};

bool d3d11_renderer::create(int64_t consumer_device_hint) {
    mpv_handle *mpv = owner_.mpv();
    if (!mpv) {
        LOG(&owner_, LOG_LEVEL_ERROR, "create_d3d11_renderer: mpv handle is null");
        return false;
    }

    // Shared textures only open on a D3D12 device of the same adapter, so follow Skia's
    // adapter when it is known; the default adapter differs from it on hybrid-GPU
    // machines (GPU preference settings, skiko.gpu.priority).
    HRESULT hr = E_FAIL;
    IDXGIAdapter *adapter = consumer_device_hint ? skia_adapter(&owner_, consumer_device_hint) : nullptr;
    if (adapter) {
        hr = create_d3d11_device(&owner_, adapter, &device_, &context_);
        adapter->Release();
        if (FAILED(hr)) {
            LOG(&owner_, LOG_LEVEL_WARN,
                "D3D11CreateDevice on Skia's adapter failed (0x%lx); using the default adapter", hr);
            safe_release(context_);
            safe_release(device_);
        }
    }
    if (!device_) hr = create_d3d11_device(&owner_, nullptr, &device_, &context_);
    if (FAILED(hr) || !device_ || !context_) {
        LOG(&owner_, LOG_LEVEL_ERROR, "D3D11CreateDevice failed: 0x%lx", hr);
        return false;
    }
    DXGI_ADAPTER_DESC adapter_desc{};
    if (d3d11_adapter_desc(device_, adapter_desc)) {
        LOG(&owner_, LOG_LEVEL_INFO, "D3D11 device on adapter '%s' (luid %08lx:%08lx)",
            adapter_name(adapter_desc).c_str(),
            adapter_desc.AdapterLuid.HighPart, adapter_desc.AdapterLuid.LowPart);
    }

    ID3D11Multithread *multithread = nullptr;
    if (SUCCEEDED(context_->QueryInterface(
            __uuidof(ID3D11Multithread), reinterpret_cast<void **>(&multithread)))) {
        multithread->SetMultithreadProtected(TRUE);
        multithread->Release();
    }

    D3D11_QUERY_DESC query_desc{D3D11_QUERY_EVENT, 0};
    if (FAILED(device_->CreateQuery(&query_desc, &flush_query_))) {
        LOG(&owner_, LOG_LEVEL_WARN, "CreateQuery(D3D11_QUERY_EVENT) failed; frame waits degrade to Flush");
        flush_query_ = nullptr;
    }

    mpv_d3d11_init_params init_params{device_};
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_D3D11)},
        {MPV_RENDER_PARAM_D3D11_INIT_PARAMS, &init_params},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    const int create_result = mpv_render_context_create(&render_context_, mpv, params);
    if (create_result < 0) {
        LOG(&owner_, LOG_LEVEL_ERROR,
            "mpv_render_context_create(d3d11) failed: %s", mpv_error_string(create_result));
        render_context_ = nullptr;
        return false;
    }
    attach_update_callback();
    return start_render_thread();
}

void d3d11_renderer::after_shutdown() {
    detach_update_callback();
    if (render_context_) {
        mpv_render_context_free(render_context_);
        render_context_ = nullptr;
    }
    safe_release(skia_device_);
    safe_release(flush_query_);
    safe_release(context_);
    safe_release(device_);
}

bool d3d11_renderer::prepare_device_locked(int64_t consumer_device, bool changed) {
    if (!changed && skia_device_) return true;
    safe_release(skia_device_);
    skia_device_ = open_skia_d3d12_device(&owner_, consumer_device);
    // consumer_device == 0 (headless/readback) legitimately yields no D3D12 side; a
    // non-zero pointer failing the layout check was already logged. Either way the ring
    // is still allocated so playback and PNG readback keep working.
    if (skia_device_) {
        const LUID skia_luid = d3d12_adapter_luid(skia_device_);
        const std::string skia_name = adapter_name_for_luid(&owner_, skia_luid);
        DXGI_ADAPTER_DESC producer_desc{};
        if (d3d11_adapter_desc(device_, producer_desc) && !same_luid(producer_desc.AdapterLuid, skia_luid)) {
            // Our device is fixed for the render context's lifetime (freeing it while
            // video is active disables video), so a Skia device that moved to another
            // adapter cannot be followed; OpenSharedHandle below will fail.
            LOG(&owner_, LOG_LEVEL_ERROR,
                "Skia's D3D12 device is on adapter '%s' but mpv's D3D11 device is on '%s'; "
                "shared video textures cannot be opened across adapters",
                skia_name.c_str(), adapter_name(producer_desc).c_str());
        } else {
            LOG(&owner_, LOG_LEVEL_INFO, "Skia D3D12 device on adapter '%s'", skia_name.c_str());
        }
    }
    return true;
}

bool d3d11_renderer::allocate_buffer(d3d11_buffer &buffer, int width, int height) {
    D3D11_TEXTURE2D_DESC desc = {};
    desc.Width = (UINT) width;
    desc.Height = (UINT) height;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    // R8G8B8A8_UNORM matches both Skia's D3D12 caps (kRGBA_8888 / kRGB_888x) and
    // Skiko's own swapchain format.
    desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    // NT-handle sharing without keyed mutex: the render thread CPU-waits for frame
    // completion before publishing, so cross-device reads never race the writer.
    desc.MiscFlags = D3D11_RESOURCE_MISC_SHARED | D3D11_RESOURCE_MISC_SHARED_NTHANDLE;

    ID3D11Texture2D *texture = nullptr;
    HRESULT hr = device_->CreateTexture2D(&desc, nullptr, &texture);
    if (FAILED(hr) || !texture) {
        LOG(&owner_, LOG_LEVEL_ERROR, "CreateTexture2D(%dx%d shared) failed: 0x%lx", width, height, hr);
        return false;
    }

    HANDLE shared_handle = nullptr;
    IDXGIResource1 *dxgi_resource = nullptr;
    hr = texture->QueryInterface(__uuidof(IDXGIResource1), reinterpret_cast<void **>(&dxgi_resource));
    if (SUCCEEDED(hr)) {
        hr = dxgi_resource->CreateSharedHandle(
            nullptr, DXGI_SHARED_RESOURCE_READ | DXGI_SHARED_RESOURCE_WRITE, nullptr, &shared_handle);
        dxgi_resource->Release();
    }
    if (FAILED(hr) || !shared_handle) {
        LOG(&owner_, LOG_LEVEL_ERROR, "CreateSharedHandle failed: 0x%lx", hr);
        texture->Release();
        return false;
    }

    ID3D12Resource *d3d12_resource = nullptr;
    if (skia_device_) {
        hr = skia_device_->OpenSharedHandle(
            shared_handle, __uuidof(ID3D12Resource), reinterpret_cast<void **>(&d3d12_resource));
        if (FAILED(hr) || !d3d12_resource) {
            LOG(&owner_, LOG_LEVEL_ERROR, "ID3D12Device::OpenSharedHandle failed: 0x%lx", hr);
            CloseHandle(shared_handle);
            texture->Release();
            return false;
        }
    }

    buffer.texture = texture;
    buffer.shared_handle = shared_handle;
    buffer.d3d12_resource = d3d12_resource;
    return true;
}

void d3d11_renderer::destroy_buffer(d3d11_buffer &buffer) {
    safe_release(buffer.d3d12_resource);
    if (buffer.shared_handle) CloseHandle(buffer.shared_handle);
    safe_release(buffer.texture);
}

bool d3d11_renderer::render_into(const d3d11_buffer &buffer) {
    if (!render_context_ || !buffer.texture) return false;

    // D3D11 render targets are top-down (row 0 = top), matching both Skia's
    // SurfaceOrigin.TOP_LEFT sampling and the PNG readback; no flip needed.
    mpv_d3d11_fbo fbo{buffer.texture, buffer_width_, buffer_height_};
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_D3D11_FBO, &fbo},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    const int render_result = mpv_render_context_render(render_context_, params);

    // The glFinish equivalent: Skia samples this texture on another device right after
    // the buffer is published, so the frame must be complete, not merely submitted.
    // Runs on the render thread, so it never blocks UI.
    wait_for_gpu();
    return render_result >= 0;
}

bool d3d11_renderer::wait_for_gpu() {
    if (!context_) return false;
    if (!flush_query_) {
        context_->Flush();
        return true;
    }
    context_->End(flush_query_);
    // Bound the spin: on a GPU hang / TDR the query never retires, and an unbounded loop
    // would peg a core forever and wedge the render thread so teardown can never join it.
    // 2s is far beyond any real frame; past it we treat the device as lost and bail.
    const ULONGLONG start_tick = GetTickCount64();
    const ULONGLONG timeout_ms = 2000;
    for (int spins = 0;; ++spins) {
        // GetData with flags 0 implicitly flushes; returns S_OK once the GPU has
        // retired everything submitted before End().
        const HRESULT hr = context_->GetData(flush_query_, nullptr, 0, 0);
        if (hr == S_OK) return true;
        if (FAILED(hr)) {
            LOG(&owner_, LOG_LEVEL_ERROR, "flush query GetData failed: 0x%lx", hr);
            return false;
        }
        if (GetTickCount64() - start_tick >= timeout_ms) {
            const HRESULT removed = device_ ? device_->GetDeviceRemovedReason() : S_OK;
            LOG(&owner_, LOG_LEVEL_ERROR,
                "wait_for_gpu timed out after %llums (device removed reason: 0x%lx)",
                timeout_ms, removed);
            return false;
        }
        if (spins < 64) {
            YieldProcessor();
        } else {
            Sleep(spins < 256 ? 0 : 1);
        }
    }
}

bool d3d11_renderer::setup_readback_locked() {
    D3D11_TEXTURE2D_DESC desc{};
    buffers_[0].texture->GetDesc(&desc);
    desc.Usage = D3D11_USAGE_STAGING;
    desc.BindFlags = 0;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    desc.MiscFlags = 0;
    const HRESULT hr = device_->CreateTexture2D(&desc, nullptr, &readback_staging_);
    if (FAILED(hr) || !readback_staging_) {
        LOG(&owner_, LOG_LEVEL_ERROR, "readback staging texture creation failed: 0x%lx", hr);
        readback_staging_ = nullptr;
        return false;
    }
    return true;
}

void d3d11_renderer::release_readback_locked() {
    safe_release(readback_staging_);
    std::vector<uint8_t>().swap(readback_scratch_);
    std::vector<uint8_t>().swap(readback_latest_);
}

bool d3d11_renderer::read_back(const d3d11_buffer &buffer) {
    if (!readback_staging_ || !buffer.texture) return false;
    context_->CopyResource(readback_staging_, buffer.texture);
    // Blocks this render thread only until the copy retires; the frame itself is
    // already complete (render_into waited for it).
    D3D11_MAPPED_SUBRESOURCE mapped{};
    const HRESULT hr = context_->Map(readback_staging_, 0, D3D11_MAP_READ, 0, &mapped);
    if (FAILED(hr)) {
        LOG(&owner_, LOG_LEVEL_ERROR, "Map(readback staging) failed: 0x%lx", hr);
        return false;
    }
    // RGBA8 top-down, like the render target; the consumer ignores alpha (opaque).
    const size_t stride = static_cast<size_t>(buffer_width_) * 4;
    readback_scratch_.resize(stride * buffer_height_);
    for (int y = 0; y < buffer_height_; ++y) {
        std::memcpy(
            readback_scratch_.data() + static_cast<size_t>(y) * stride,
            static_cast<const uint8_t *>(mapped.pData) + static_cast<size_t>(y) * mapped.RowPitch,
            stride);
    }
    context_->Unmap(readback_staging_, 0);
    return true;
}

uint64_t d3d11_renderer::copy_latest_frame(void *dest, int width, int height) {
    if (!dest || width <= 0 || height <= 0) return 0;
    std::lock_guard<std::mutex> guard(mutex_);
    // latest_index_ is reset on every reconfig and set again only after a frame of the
    // new size was read back, so a published frame always has the buffer size.
    if (!cpu_readback_ || latest_index_ < 0 || width != buffer_width_ || height != buffer_height_) {
        return 0;
    }
    const size_t size = static_cast<size_t>(width) * height * 4;
    if (readback_latest_.size() < size) return 0;
    std::memcpy(dest, readback_latest_.data(), size);
    return frame_state();
}

// Copies a rendered texture into ARGB_8888 ints through a staging texture; independent
// of mpv's screenshot pipeline, which cannot convert hwdec (d3d11va) frames without zimg.
bool d3d11_renderer::read_texture_argb(
    ID3D11Texture2D *source, std::vector<uint32_t> &out_pixels, int &out_width, int &out_height) {
    if (!source || !device_ || !context_) return false;

    D3D11_TEXTURE2D_DESC desc = {};
    source->GetDesc(&desc);
    desc.Usage = D3D11_USAGE_STAGING;
    desc.BindFlags = 0;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    desc.MiscFlags = 0;

    ID3D11Texture2D *staging = nullptr;
    if (FAILED(device_->CreateTexture2D(&desc, nullptr, &staging)) || !staging) {
        LOG(&owner_, LOG_LEVEL_ERROR, "staging texture creation failed");
        return false;
    }
    context_->CopyResource(staging, source);

    D3D11_MAPPED_SUBRESOURCE mapped = {};
    if (FAILED(context_->Map(staging, 0, D3D11_MAP_READ, 0, &mapped))) {
        LOG(&owner_, LOG_LEVEL_ERROR, "Map(staging) failed");
        staging->Release();
        return false;
    }

    const UINT width = desc.Width, height = desc.Height;
    out_pixels.resize((size_t) width * height);
    for (UINT y = 0; y < height; ++y) {
        const auto *src = (const uint8_t *) mapped.pData + (size_t) y * mapped.RowPitch;
        uint32_t *dst = out_pixels.data() + (size_t) y * width;
        for (UINT x = 0; x < width; ++x) {
            dst[x] = 0xFF000000u | ((uint32_t) src[x * 4] << 16) |
                ((uint32_t) src[x * 4 + 1] << 8) | src[x * 4 + 2];
        }
    }
    context_->Unmap(staging, 0);
    staging->Release();
    out_width = (int) width;
    out_height = (int) height;
    return true;
}

// mutex_ keeps the render thread from cycling the ring onto the buffer being read.
bool d3d11_renderer::read_surface_pixels(std::vector<uint32_t> &pixels, int &width, int &height) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (!buffers_allocated_ || latest_index_ < 0) return false;
    return read_texture_argb(buffers_[latest_index_].texture, pixels, width, height);
}

// A private render target of the requested size: mpv redraws the current frame into it
// (the ring and its consumer are untouched); the GPU wait and the staging copy follow
// the ring's own protocol.
bool d3d11_renderer::render_frame_pixels_on_render_thread(int width, int height, std::vector<uint32_t> &pixels) {
    if (!render_context_ || !device_) return false;
    D3D11_TEXTURE2D_DESC desc = {};
    desc.Width = (UINT) width;
    desc.Height = (UINT) height;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    ID3D11Texture2D *target = nullptr;
    const HRESULT hr = device_->CreateTexture2D(&desc, nullptr, &target);
    if (FAILED(hr) || !target) {
        LOG(&owner_, LOG_LEVEL_ERROR, "CreateTexture2D(%dx%d frame request) failed: 0x%lx", width, height, hr);
        return false;
    }
    mpv_d3d11_fbo fbo{target, width, height};
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_D3D11_FBO, &fbo},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    bool ok = mpv_render_context_render(render_context_, params) >= 0;
    ok = wait_for_gpu() && ok;
    int read_width = 0, read_height = 0;
    if (ok) ok = read_texture_argb(target, pixels, read_width, read_height);
    target->Release();
    return ok;
}

} // namespace

std::shared_ptr<desktop_renderer> create_d3d11_renderer(mpv_handle_t &owner, int64_t consumer_device_hint) {
    auto renderer = std::make_shared<d3d11_renderer>(owner);
    if (!renderer->create(consumer_device_hint)) return nullptr;  // the destructor cleans up
    return renderer;
}

} // namespace mediampv

#endif // _WIN32
