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
// Threading model: the render thread is the only thread that renders or mutates the
// buffer ring. mpv's update callback, buffer reconfiguration (resize) and consumer acks
// are requests posted under render_mutex_; consumers read the packed frame_state_ and
// sample the latest buffer. The immediate context is put in multithread-protected mode
// so the screenshot readback (JNI thread) can CopyResource/Map while the render thread
// is inside mpv_render_context_render.
//
// mpv leaves the alpha channel undefined for opaque video (see render_macos.mm); the
// consumer ignores it by wrapping the texture with an opaque color type (RGB_888X), and
// the CPU readbacks (PNG/pixels) force alpha to 255. No native alpha-fix pass is needed.

#ifdef _WIN32

#include <initguid.h>
#include <windows.h>
#include <d3d11_4.h>
#include <d3d12.h>
#include <dxgi1_2.h>
#include <wincodec.h>

#include <cstdint>
#include <cstring>
#include <thread>
#include <vector>

#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_d3d11.h>

#include "mpv_handle_t.h"
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

struct scoped_co_init final {
    scoped_co_init() {
        HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        // RPC_E_CHANGED_MODE: already initialized as STA on this thread; usable as-is.
        initialized = SUCCEEDED(hr);
        usable = initialized || hr == RPC_E_CHANGED_MODE;
    }
    ~scoped_co_init() {
        if (initialized) CoUninitialize();
    }
    bool initialized = false;
    bool usable = false;
};

}  // namespace

namespace mediampv {

bool mpv_handle_t::create_render_context() {
    if (!handle_) {
        LOG(this, LOG_LEVEL_ERROR, "create_render_context: mpv handle is null");
        return false;
    }
    if (render_context_) return true;

    // VIDEO_SUPPORT is required for FFmpeg's d3d11va hwdevice_ctx (hwdec) to attach to
    // this device; retried without it for drivers/WARP levels that reject the flag
    // (playback then falls back to software decoding but rendering still works).
    const UINT flag_sets[] = {
        D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT,
        D3D11_CREATE_DEVICE_BGRA_SUPPORT,
    };
    const D3D_DRIVER_TYPE driver_types[] = {D3D_DRIVER_TYPE_HARDWARE, D3D_DRIVER_TYPE_WARP};
    const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
    ID3D11Device *device = nullptr;
    ID3D11DeviceContext *context = nullptr;
    HRESULT hr = E_FAIL;
    for (D3D_DRIVER_TYPE driver_type : driver_types) {
        for (UINT flags : flag_sets) {
            hr = D3D11CreateDevice(
                nullptr, driver_type, nullptr, flags,
                levels, ARRAYSIZE(levels), D3D11_SDK_VERSION, &device, nullptr, &context);
            if (hr == E_INVALIDARG) {
                // Pre-11.1 runtime rejects the 11_1 entry; retry without it.
                hr = D3D11CreateDevice(
                    nullptr, driver_type, nullptr, flags,
                    levels + 1, ARRAYSIZE(levels) - 1, D3D11_SDK_VERSION, &device, nullptr, &context);
            }
            if (SUCCEEDED(hr)) break;
            if (!(flags & D3D11_CREATE_DEVICE_VIDEO_SUPPORT)) {
                // Headless CI / no GPU: WARP renders in software but supports the full
                // API, including shared resources (Windows 8+).
                LOG(this, LOG_LEVEL_WARN,
                    "D3D11CreateDevice(type=%d flags=0x%x) failed (0x%lx)",
                    (int) driver_type, flags, hr);
            }
        }
        if (SUCCEEDED(hr)) break;
    }
    if (FAILED(hr) || !device || !context) {
        LOG(this, LOG_LEVEL_ERROR, "D3D11CreateDevice failed: 0x%lx", hr);
        safe_release(context);
        safe_release(device);
        return false;
    }

    // The screenshot readback uses the immediate context from the JNI thread while the
    // render thread may be inside mpv; make the context internally synchronized.
    ID3D11Multithread *multithread = nullptr;
    if (SUCCEEDED(context->QueryInterface(
            __uuidof(ID3D11Multithread), reinterpret_cast<void **>(&multithread)))) {
        multithread->SetMultithreadProtected(TRUE);
        multithread->Release();
    }

    D3D11_QUERY_DESC query_desc{D3D11_QUERY_EVENT, 0};
    if (FAILED(device->CreateQuery(&query_desc, &flush_query_))) {
        LOG(this, LOG_LEVEL_WARN, "CreateQuery(D3D11_QUERY_EVENT) failed; frame waits degrade to Flush");
        flush_query_ = nullptr;
    }

    mpv_d3d11_init_params init_params{device};
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_D3D11)},
        {MPV_RENDER_PARAM_D3D11_INIT_PARAMS, &init_params},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    int create_result = mpv_render_context_create(&render_context_, handle_, params);
    if (create_result < 0) {
        LOG(this, LOG_LEVEL_ERROR,
            "mpv_render_context_create(d3d11) failed: %s", mpv_error_string(create_result));
        render_context_ = nullptr;
        safe_release(flush_query_);
        safe_release(context);
        safe_release(device);
        return false;
    }

    d3d_device_ = device;
    d3d_context_ = context;
    mpv_render_context_set_update_callback(render_context_, &mpv_handle_t::on_render_update, this);
    start_render_thread();
    return true;
}

bool mpv_handle_t::destroy_render_context() {
    cleanup_render_resources();
    return true;
}

bool mpv_handle_t::set_surface_config(int width, int height, int64_t skiko_device_ptr) {
    if (!render_thread_) return false;
    {
        std::lock_guard<std::mutex> guard(render_mutex_);
        pending_width_ = width;
        pending_height_ = height;
        pending_device_ptr_ = skiko_device_ptr;
        config_pending_ = true;
    }
    render_cv_.notify_all();
    return true;
}

uint64_t mpv_handle_t::get_frame_state() {
    return frame_state_.load(std::memory_order_acquire);
}

int64_t mpv_handle_t::get_buffer_texture(int index) {
    std::lock_guard<std::mutex> guard(render_mutex_);
    if (!buffers_allocated_ || index < 0 || index >= kD3D11BufferCount) return 0;
    return (int64_t) (uintptr_t) buffers_[index].d3d12_resource;
}

bool mpv_handle_t::ack_retired_buffers() {
    {
        std::lock_guard<std::mutex> guard(render_mutex_);
        retire_ack_pending_ = true;
    }
    render_cv_.notify_all();
    return true;
}

bool mpv_handle_t::has_d3d11_surface() {
    std::lock_guard<std::mutex> guard(render_mutex_);
    return buffers_allocated_;
}

// ---- render thread ----

void mpv_handle_t::signal_render_update() {
    {
        std::lock_guard<std::mutex> guard(render_mutex_);
        render_pending_ = true;
    }
    render_cv_.notify_all();
}

void mpv_handle_t::start_render_thread() {
    if (render_thread_) return;
    render_quit_ = false;
    render_thread_ = new std::thread([this] { render_thread_loop(); });
}

void mpv_handle_t::stop_render_thread() {
    if (!render_thread_) return;
    {
        std::lock_guard<std::mutex> guard(render_mutex_);
        render_quit_ = true;
    }
    render_cv_.notify_all();
    auto *thread = (std::thread *) render_thread_;
    if (thread->joinable()) thread->join();
    delete thread;
    render_thread_ = nullptr;
}

void mpv_handle_t::render_thread_loop() {
    // Pre-attach so the per-frame notify_render_update() is a cheap GetEnv, not an
    // attach/detach pair.
    JNIEnv *thread_env = nullptr;
    bool attached = jvm_ &&
        jvm_->AttachCurrentThread(reinterpret_cast<void **>(&thread_env), nullptr) == JNI_OK;

    std::unique_lock<std::mutex> lock(render_mutex_);
    while (!render_quit_) {
        render_cv_.wait(lock, [this] {
            return render_quit_ || render_pending_ || config_pending_ || retire_ack_pending_;
        });
        if (render_quit_) break;

        if (retire_ack_pending_) {
            retire_ack_pending_ = false;
            if (has_retired_buffers_) {
                destroy_buffer_ring(retired_buffers_);
                has_retired_buffers_ = false;
            }
        }

        // A reconfig retires the current ring; never stack a second retirement on top
        // of an unacked one (the consumer may still be sampling it) — postpone until
        // the ack arrives.
        bool configured = false;
        if (config_pending_ && !has_retired_buffers_) {
            config_pending_ = false;
            configured = apply_config_locked();
        }

        bool want_render = render_pending_;
        render_pending_ = false;

        if (!buffers_allocated_) {
            // With vo=libmpv, playback stalls unless someone consumes video frames.
            // While no surface is configured (headless probing, surface not composed
            // yet), discard them so the playback clock keeps advancing.
            if (want_render) {
                lock.unlock();
                drain_one_frame();
                lock.lock();
            }
            continue;
        }

        bool has_new_frame = false;
        if (want_render && render_context_) {
            has_new_frame =
                (mpv_render_context_update(render_context_) & MPV_RENDER_UPDATE_FRAME) != 0;
        }
        // After a reconfig, redraw the current frame into the new ring even if mpv has
        // nothing new (e.g. resizing while paused).
        if (!has_new_frame && !configured) continue;

        int next = (latest_index_ + 1) % kD3D11BufferCount;
        d3d11_buffer target = buffers_[next];
        lock.unlock();
        bool rendered = render_into(target);
        lock.lock();
        if (rendered) {
            latest_index_ = next;
            ++frame_serial_;
            publish_state_locked();
            lock.unlock();
            // Notify only after the frame is actually complete in the shared texture,
            // so a consumer waking on this never samples a stale buffer.
            notify_render_update();
            lock.lock();
        }
    }
    lock.unlock();
    if (attached) jvm_->DetachCurrentThread();
}

bool mpv_handle_t::apply_config_locked() {
    const int width = pending_width_, height = pending_height_;
    const int64_t device_ptr = pending_device_ptr_;

    if (width <= 0 || height <= 0) {
        // Deactivate. The consumer drops all texture references before requesting
        // this, so both generations can be freed immediately.
        if (has_retired_buffers_) {
            destroy_buffer_ring(retired_buffers_);
            has_retired_buffers_ = false;
        }
        if (buffers_allocated_) {
            destroy_buffer_ring(buffers_);
            buffers_allocated_ = false;
        }
        safe_release(skia_device_);
        latest_index_ = -1;
        buffer_width_ = buffer_height_ = 0;
        buffer_device_ptr_ = 0;
        ++buffer_generation_;
        publish_state_locked();
        return false;
    }
    if (buffers_allocated_ && width == buffer_width_ && height == buffer_height_ &&
        device_ptr == buffer_device_ptr_) {
        return false;
    }

    if (buffers_allocated_) {
        for (int i = 0; i < kD3D11BufferCount; ++i) {
            retired_buffers_[i] = buffers_[i];
            buffers_[i] = d3d11_buffer{};
        }
        has_retired_buffers_ = true;
        buffers_allocated_ = false;
    }

    if (device_ptr != buffer_device_ptr_ || !skia_device_) {
        safe_release(skia_device_);
        skia_device_ = open_skia_d3d12_device(this, device_ptr);
        // device_ptr == 0 (headless) legitimately yields no D3D12 side; a non-zero
        // pointer failing the layout check was already logged. Either way the ring is
        // still allocated so playback and PNG readback keep working.
    }

    bool ok = true;
    for (int i = 0; i < kD3D11BufferCount && ok; ++i) {
        ok = allocate_buffer(buffers_[i], width, height);
    }
    if (!ok) {
        LOG(this, LOG_LEVEL_ERROR, "buffer ring allocation failed (%dx%d)", width, height);
        destroy_buffer_ring(buffers_);
        latest_index_ = -1;
        buffer_width_ = buffer_height_ = 0;
        buffer_device_ptr_ = 0;
        ++buffer_generation_;
        publish_state_locked();
        return false;
    }

    buffers_allocated_ = true;
    buffer_width_ = width;
    buffer_height_ = height;
    buffer_device_ptr_ = device_ptr;
    latest_index_ = -1;
    ++buffer_generation_;
    publish_state_locked();
    LOG(this, LOG_LEVEL_INFO, "buffer ring allocated %dx%d gen=%u d3d12=%d",
        width, height, buffer_generation_, skia_device_ ? 1 : 0);
    return true;
}

bool mpv_handle_t::allocate_buffer(d3d11_buffer &buffer, int width, int height) {
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
    HRESULT hr = d3d_device_->CreateTexture2D(&desc, nullptr, &texture);
    if (FAILED(hr) || !texture) {
        LOG(this, LOG_LEVEL_ERROR,
            "CreateTexture2D(%dx%d shared) failed: 0x%lx", width, height, hr);
        return false;
    }

    HANDLE shared_handle = nullptr;
    IDXGIResource1 *dxgi_resource = nullptr;
    hr = texture->QueryInterface(
        __uuidof(IDXGIResource1), reinterpret_cast<void **>(&dxgi_resource));
    if (SUCCEEDED(hr)) {
        hr = dxgi_resource->CreateSharedHandle(
            nullptr, DXGI_SHARED_RESOURCE_READ | DXGI_SHARED_RESOURCE_WRITE,
            nullptr, &shared_handle);
        dxgi_resource->Release();
    }
    if (FAILED(hr) || !shared_handle) {
        LOG(this, LOG_LEVEL_ERROR, "CreateSharedHandle failed: 0x%lx", hr);
        texture->Release();
        return false;
    }

    ID3D12Resource *d3d12_resource = nullptr;
    if (skia_device_) {
        hr = skia_device_->OpenSharedHandle(
            shared_handle, __uuidof(ID3D12Resource), reinterpret_cast<void **>(&d3d12_resource));
        if (FAILED(hr) || !d3d12_resource) {
            LOG(this, LOG_LEVEL_ERROR, "ID3D12Device::OpenSharedHandle failed: 0x%lx", hr);
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

void mpv_handle_t::destroy_buffer_ring(d3d11_buffer *ring) {
    for (int i = 0; i < kD3D11BufferCount; ++i) {
        d3d11_buffer &buffer = ring[i];
        safe_release(buffer.d3d12_resource);
        if (buffer.shared_handle) CloseHandle(buffer.shared_handle);
        safe_release(buffer.texture);
        buffer = d3d11_buffer{};
    }
}

void mpv_handle_t::publish_state_locked() {
    uint64_t index_bits = latest_index_ < 0 ? 0xFull : (uint64_t) latest_index_;
    frame_state_.store(
        ((uint64_t) (buffer_generation_ & 0xFFFFu) << 48) |
        (index_bits << 44) |
        ((uint64_t) (buffer_width_ & 0x3FFF) << 30) |
        ((uint64_t) (buffer_height_ & 0x3FFF) << 16) |
        (frame_serial_ & 0xFFFFu),
        std::memory_order_release);
}

bool mpv_handle_t::render_into(const d3d11_buffer &buffer) {
    if (!render_context_ || !buffer.texture) return false;

    // D3D11 render targets are top-down (row 0 = top), matching both Skia's
    // SurfaceOrigin.TOP_LEFT sampling and the PNG readback; no flip needed.
    mpv_d3d11_fbo fbo{buffer.texture, buffer_width_, buffer_height_};
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_D3D11_FBO, &fbo},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    int render_result = mpv_render_context_render(render_context_, params);

    // The glFinish equivalent: Skia samples this texture on another device right after
    // the buffer is published, so the frame must be complete, not merely submitted.
    // Runs on the render thread, so it never blocks UI.
    wait_for_gpu();
    return render_result >= 0;
}

bool mpv_handle_t::wait_for_gpu() {
    if (!d3d_context_) return false;
    if (!flush_query_) {
        d3d_context_->Flush();
        return true;
    }
    d3d_context_->End(flush_query_);
    // Bound the spin: on a GPU hang / TDR the query never retires, and an unbounded loop
    // would peg a core forever and wedge the render thread so teardown can never join it.
    // 2s is far beyond any real frame; past it we treat the device as lost and bail.
    const ULONGLONG start_tick = GetTickCount64();
    const ULONGLONG timeout_ms = 2000;
    for (int spins = 0;; ++spins) {
        // GetData with flags 0 implicitly flushes; returns S_OK once the GPU has
        // retired everything submitted before End().
        HRESULT hr = d3d_context_->GetData(flush_query_, nullptr, 0, 0);
        if (hr == S_OK) return true;
        if (FAILED(hr)) {
            LOG(this, LOG_LEVEL_ERROR, "flush query GetData failed: 0x%lx", hr);
            return false;
        }
        if (GetTickCount64() - start_tick >= timeout_ms) {
            HRESULT removed = d3d_device_ ? d3d_device_->GetDeviceRemovedReason() : S_OK;
            LOG(this, LOG_LEVEL_ERROR,
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

void mpv_handle_t::drain_one_frame() {
    if (!render_context_) return;
    mpv_render_context_update(render_context_);
    int skip = 1;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_SKIP_RENDERING, &skip},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    mpv_render_context_render(render_context_, params);
}

void mpv_handle_t::cleanup_render_resources() {
    stop_render_thread();

    {
        std::lock_guard<std::mutex> guard(render_mutex_);
        if (has_retired_buffers_) {
            destroy_buffer_ring(retired_buffers_);
            has_retired_buffers_ = false;
        }
        if (buffers_allocated_) {
            destroy_buffer_ring(buffers_);
            buffers_allocated_ = false;
        }
        safe_release(skia_device_);
        latest_index_ = -1;
        buffer_width_ = buffer_height_ = 0;
        buffer_device_ptr_ = 0;
        publish_state_locked();
    }

    if (render_context_) {
        mpv_render_context_set_update_callback(render_context_, nullptr, nullptr);
        mpv_render_context_free(render_context_);
        render_context_ = nullptr;
    }

    safe_release(flush_query_);
    safe_release(d3d_context_);
    safe_release(d3d_device_);
}

// Staging-texture readback of the latest rendered frame (RGBA shared texture) into
// ARGB_8888 ints (0xAARRGGBB, which is BGRA byte order in little-endian memory) with
// alpha forced opaque (mpv leaves it undefined). The caller holds render_mutex_ so the
// render thread cannot cycle the ring back onto this buffer mid-read; the immediate
// context is multithread-protected, so using it here while the render thread renders
// is safe.
bool mpv_handle_t::read_frame_argb_locked(
    std::vector<uint32_t> &out_pixels, int &out_width, int &out_height) {
    if (!buffers_allocated_ || latest_index_ < 0 || !d3d_device_ || !d3d_context_) {
        return false;
    }
    ID3D11Texture2D *source = buffers_[latest_index_].texture;
    if (!source) return false;

    D3D11_TEXTURE2D_DESC desc = {};
    source->GetDesc(&desc);
    desc.Usage = D3D11_USAGE_STAGING;
    desc.BindFlags = 0;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    desc.MiscFlags = 0;

    ID3D11Texture2D *staging = nullptr;
    if (FAILED(d3d_device_->CreateTexture2D(&desc, nullptr, &staging)) || !staging) {
        LOG(this, LOG_LEVEL_ERROR, "staging texture creation failed");
        return false;
    }
    d3d_context_->CopyResource(staging, source);

    D3D11_MAPPED_SUBRESOURCE mapped = {};
    if (FAILED(d3d_context_->Map(staging, 0, D3D11_MAP_READ, 0, &mapped))) {
        LOG(this, LOG_LEVEL_ERROR, "Map(staging) failed");
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
    d3d_context_->Unmap(staging, 0);
    staging->Release();
    out_width = (int) width;
    out_height = (int) height;
    return true;
}

bool mpv_handle_t::read_surface_pixels(
    std::vector<uint32_t> &out_pixels, int &out_width, int &out_height) {
    std::lock_guard<std::mutex> guard(render_mutex_);
    return read_frame_argb_locked(out_pixels, out_width, out_height);
}

// Writes the latest rendered frame as PNG via the staging-texture readback and WIC.
// Independent of mpv's screenshot pipeline, which cannot convert hwdec (d3d11va)
// frames without zimg. render_mutex_ is only held for the readback; the encode works
// on our own copy.
bool mpv_handle_t::save_surface_png(const char *path) {
    if (!path) return false;
    std::vector<uint32_t> pixels;
    int width = 0, height = 0;
    {
        std::lock_guard<std::mutex> guard(render_mutex_);
        if (!read_frame_argb_locked(pixels, width, height)) return false;
    }

    scoped_co_init com;
    if (!com.usable) {
        LOG(this, LOG_LEVEL_ERROR, "CoInitializeEx failed");
        return false;
    }
    bool ok = false;
    IWICImagingFactory *factory = nullptr;
    IWICStream *stream = nullptr;
    IWICBitmapEncoder *encoder = nullptr;
    IWICBitmapFrameEncode *frame = nullptr;
    do {
        if (FAILED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
                                    __uuidof(IWICImagingFactory),
                                    reinterpret_cast<void **>(&factory)))) break;
        if (FAILED(factory->CreateStream(&stream))) break;
        int wide_length = MultiByteToWideChar(CP_UTF8, 0, path, -1, nullptr, 0);
        std::vector<wchar_t> wide_path((size_t) (wide_length > 0 ? wide_length : 1));
        MultiByteToWideChar(CP_UTF8, 0, path, -1, wide_path.data(), wide_length);
        if (FAILED(stream->InitializeFromFilename(wide_path.data(), GENERIC_WRITE))) break;
        if (FAILED(factory->CreateEncoder(GUID_ContainerFormatPng, nullptr, &encoder))) break;
        if (FAILED(encoder->Initialize(stream, WICBitmapEncoderNoCache))) break;
        if (FAILED(encoder->CreateNewFrame(&frame, nullptr))) break;
        if (FAILED(frame->Initialize(nullptr))) break;
        if (FAILED(frame->SetSize((UINT) width, (UINT) height))) break;
        // ARGB ints are BGRA bytes in little-endian memory.
        WICPixelFormatGUID format = GUID_WICPixelFormat32bppBGRA;
        if (FAILED(frame->SetPixelFormat(&format))) break;
        if (FAILED(frame->WritePixels(
                (UINT) height, (UINT) width * 4, (UINT) (pixels.size() * 4),
                reinterpret_cast<BYTE *>(pixels.data())))) break;
        if (FAILED(frame->Commit())) break;
        if (FAILED(encoder->Commit())) break;
        ok = true;
    } while (false);
    safe_release(frame);
    safe_release(encoder);
    safe_release(stream);
    safe_release(factory);
    if (!ok) LOG(this, LOG_LEVEL_ERROR, "save_surface_png failed for %s", path);
    return ok;
}

}  // namespace mediampv

#endif  // _WIN32
