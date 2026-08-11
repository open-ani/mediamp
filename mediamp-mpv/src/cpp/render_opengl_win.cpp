/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

// Windows OpenGL fallback render path, used when Compose renders with Skiko's OpenGL
// backend (SKIKO_RENDER_API=OPENGL) instead of the Direct3D default — Skia then has no
// D3D12 device, so the D3D11 shared-texture path (render_d3d11.cpp) cannot exist.
//
// This is deliberately the simplest correct path, not the fastest one: a dedicated
// render thread drives mpv through the libmpv OpenGL render API on a private offscreen
// WGL context that shares nothing with Skiko, renders into a single FBO, and reads
// every frame back to CPU memory (double-buffered: the thread fills a scratch buffer
// while unlocked, then swaps it in under the state mutex). The consumer copies the
// latest frame into a Skia bitmap during a Compose draw and uploads it there. One
// GPU->CPU->GPU round trip per frame by design — no wglShareLists, no pixel-format
// matching against Skiko's drawable, no cross-context object lifetime rules; exactly
// the parts that make context sharing fragile across drivers.
//
// Threading model: the render thread exclusively owns the WGL context, the FBO and the
// scratch buffer, and is the only thread that renders. Requests (resize, mpv's update
// callback) are flags posted under the state mutex. Consumers read the packed frame
// state (atomic) and copy the latest CPU frame under the mutex; the mutex is never held
// across mpv rendering or the glReadPixels, so a consumer copy can only ever wait for a
// buffer swap, not for a frame.

#ifdef _WIN32

#include <windows.h>

#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_gl.h>

#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#include "gl_functions_win.h"
#include "log.h"
#include "mpv_handle_t.h"
#include "png_writer_win.h"
#include "wgl_offscreen_context.h"

namespace {

void *win_gl_get_proc_address(void *ctx, const char *name) {
    auto *context = static_cast<mediampv::wgl_offscreen_context *>(ctx);
    return context ? context->get_proc_address(name) : nullptr;
}

// glGetError pops one error from a queue that anyone (including mpv's renderer) may
// have left non-empty; drain it before a checked sequence so a stale error cannot
// fail an unrelated operation.
void drain_gl_errors() {
    while (glGetError() != GL_NO_ERROR) {
    }
}

} // namespace

namespace mediampv {

struct mpv_handle_t::win_gl_state final {
    // Owned by the render thread between initialization and thread exit.
    mpv_render_context *render_context = nullptr;
    wgl_offscreen_context *gl = nullptr;
    GLuint fbo = 0, texture = 0;

    // Guarded by mutex, except scratch: only the render thread touches scratch, and
    // the scratch/latest swap happens under the mutex, so a consumer copying latest
    // never observes a buffer the render thread is writing into.
    std::vector<uint8_t> scratch, latest; // RGBA8, glReadPixels bottom-up row order
    int width = 0, height = 0;            // active surface size (0 = deactivated)
    int latest_width = 0, latest_height = 0;
    bool has_frame = false;
    uint32_t generation = 0;
    uint64_t serial = 0;
    std::atomic<uint64_t> frame_state{0xFull << 44}; // "no frame" sentinel

    // Requests to the render thread; guarded by mutex. The serial pair lets a
    // deactivation request wait until the render thread has actually applied it.
    bool config_pending = false;
    int pending_width = 0, pending_height = 0;
    uint64_t config_request_serial = 0, config_applied_serial = 0;
    bool render_pending = false;
    bool quit = false;
    bool initialized = false, initialize_ok = false;
    std::mutex mutex;
    std::condition_variable cv;
    std::thread *thread = nullptr;
};

bool mpv_handle_t::create_render_context_win_gl() {
    if (!handle_) {
        LOGE("create_render_context_win_gl: mpv handle is null");
        return false;
    }
    if (win_gl_ && win_gl_->thread) return true;
    if (!win_gl_) win_gl_ = new win_gl_state();
    auto *s = win_gl_;
    {
        std::lock_guard<std::mutex> lock(s->mutex);
        s->quit = false;
        s->initialized = false;
        s->initialize_ok = false;
    }
    s->thread = new std::thread([this] { render_thread_loop_win_gl(); });
    std::unique_lock<std::mutex> lock(s->mutex);
    s->cv.wait(lock, [s] { return s->initialized; });
    const bool initialized = s->initialize_ok;
    lock.unlock();
    if (!initialized) cleanup_render_resources_win_gl();
    return initialized;
}

bool mpv_handle_t::destroy_render_context_win_gl() {
    cleanup_render_resources_win_gl();
    return true;
}

void mpv_handle_t::free_win_gl_state() {
    delete win_gl_;
    win_gl_ = nullptr;
}

void mpv_handle_t::cleanup_render_resources_win_gl() {
    auto *s = win_gl_;
    if (!s || !s->thread) return;
    LOGI("OpenGL fallback teardown: stopping the render thread");
    {
        std::lock_guard<std::mutex> lock(s->mutex);
        s->quit = true;
    }
    s->cv.notify_all();
    if (s->thread->joinable()) s->thread->join();
    delete s->thread;
    s->thread = nullptr;
    LOGI("OpenGL fallback teardown: render thread joined");
    // The state struct itself stays alive for the handle's lifetime (freed in the
    // destructor): consumers may still be polling get_frame_state_win_gl or
    // copy_latest_frame_win_gl concurrently with this teardown, and they check
    // has_frame/thread under the mutex.
}

bool mpv_handle_t::set_surface_config_win_gl(int width, int height) {
    auto *s = win_gl_;
    if (!s || !s->thread) return false;
    uint64_t request;
    {
        std::lock_guard<std::mutex> lock(s->mutex);
        s->pending_width = width;
        s->pending_height = height;
        s->config_pending = true; // newest request replaces an unprocessed resize
        request = ++s->config_request_serial;
    }
    s->cv.notify_all();
    if (width > 0 && height > 0) return true;
    // Deactivation is synchronous: the consumer releases its Skia GPU objects right
    // after this call, and destroying them on Skiko's GL context while this path's
    // producer context is still actively rendering can block in the driver's
    // cross-context synchronization. Wait until the render thread has dropped the FBO
    // and parked (it then only drains frames without touching GL). Bounded: the
    // thread is at worst one frame render away, and the timeout means a wedged
    // producer degrades to the old behavior instead of hanging the caller.
    std::unique_lock<std::mutex> lock(s->mutex);
    const bool applied = s->cv.wait_for(lock, std::chrono::seconds(1), [s, request] {
        return s->quit || s->config_applied_serial >= request;
    });
    if (!applied) {
        LOGW("OpenGL fallback surface deactivation was not acknowledged within 1s");
    }
    return true;
}

uint64_t mpv_handle_t::get_frame_state_win_gl() {
    auto *s = win_gl_;
    return s ? s->frame_state.load(std::memory_order_acquire) : (0xFull << 44);
}

bool mpv_handle_t::has_win_gl_surface() {
    auto *s = win_gl_;
    if (!s) return false;
    std::lock_guard<std::mutex> lock(s->mutex);
    return s->width > 0;
}

void mpv_handle_t::on_render_update_win_gl(void *context) {
    auto *instance = static_cast<mpv_handle_t *>(context);
    if (instance) instance->signal_render_update_win_gl();
}

void mpv_handle_t::signal_render_update_win_gl() {
    auto *s = win_gl_;
    if (!s) return;
    {
        std::lock_guard<std::mutex> lock(s->mutex);
        s->render_pending = true;
    }
    s->cv.notify_all();
}

void mpv_handle_t::render_thread_loop_win_gl() {
    auto *s = win_gl_;
    // Pre-attach so the per-frame notify_render_update() is a cheap GetEnv, not an
    // attach/detach pair.
    JNIEnv *thread_env = nullptr;
    const bool attached = jvm_ &&
        jvm_->AttachCurrentThread(reinterpret_cast<void **>(&thread_env), nullptr) == JNI_OK;

    std::string error;
    s->gl = wgl_offscreen_context::create(&error);
    if (!s->gl) LOGE("cannot create the offscreen WGL fallback context: %s", error.c_str());
    bool initialized = s->gl != nullptr;
    if (initialized) {
        mpv_opengl_init_params gl_init_params{
            .get_proc_address = win_gl_get_proc_address,
            .get_proc_address_ctx = s->gl,
        };
        mpv_render_param params[] = {
            {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
            {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init_params},
            {MPV_RENDER_PARAM_INVALID, nullptr},
        };
        const int result = mpv_render_context_create(&s->render_context, handle_, params);
        if (result < 0) {
            s->render_context = nullptr;
            LOGE("mpv_render_context_create(OpenGL fallback) failed: %s", mpv_error_string(result));
            initialized = false;
        } else {
            mpv_render_context_set_update_callback(
                s->render_context, &mpv_handle_t::on_render_update_win_gl, this);
        }
    }
    {
        std::lock_guard<std::mutex> lock(s->mutex);
        s->initialize_ok = initialized;
        s->initialized = true;
    }
    s->cv.notify_all();
    if (!initialized) {
        if (s->gl) {
            s->gl->destroy();
            delete s->gl;
            s->gl = nullptr;
        }
        if (attached) jvm_->DetachCurrentThread();
        return;
    }

    std::unique_lock<std::mutex> lock(s->mutex);
    while (!s->quit) {
        s->cv.wait(lock, [s] { return s->quit || s->render_pending || s->config_pending; });
        if (s->quit) break;

        bool configured = false;
        if (s->config_pending) {
            s->config_pending = false;
            configured = apply_config_win_gl_locked();
            // Acknowledges every request posted so far (requests coalesce; the apply
            // above used the latest values). Deactivation waits on this.
            s->config_applied_serial = s->config_request_serial;
            s->cv.notify_all();
        }
        const bool want_render = s->render_pending;
        s->render_pending = false;
        if (s->fbo == 0) {
            if (want_render) {
                lock.unlock();
                drain_one_frame_win_gl();
                lock.lock();
            }
            continue;
        }
        bool has_new_frame = false;
        if (want_render) {
            has_new_frame = (mpv_render_context_update(s->render_context) & MPV_RENDER_UPDATE_FRAME) != 0;
        }
        // A fresh config renders even without a new mpv frame so a paused video is
        // re-rendered at the new size.
        if (!has_new_frame && !configured) continue;
        const int width = s->width, height = s->height;
        lock.unlock();
        const bool rendered = render_frame_win_gl(width, height);
        lock.lock();
        // The surface cannot have been reconfigured meanwhile: only this thread
        // applies config changes.
        if (rendered) {
            std::swap(s->scratch, s->latest);
            s->latest_width = width;
            s->latest_height = height;
            s->has_frame = true;
            ++s->serial;
            publish_state_win_gl_locked();
            lock.unlock();
            notify_render_update(); // release-store has completed before this JNI callback
            lock.lock();
        }
    }
    // Teardown in the owner thread while the WGL context is current.
    if (s->fbo) gl::delete_framebuffers(1, &s->fbo);
    if (s->texture) glDeleteTextures(1, &s->texture);
    s->fbo = 0;
    s->texture = 0;
    s->width = s->height = 0;
    s->has_frame = false;
    s->latest_width = s->latest_height = 0;
    ++s->generation;
    publish_state_win_gl_locked();
    lock.unlock();
    // Outside the lock: freeing the render context synchronizes with an in-flight
    // update callback, and that callback takes the state mutex.
    if (s->render_context) {
        LOGI("OpenGL fallback teardown: freeing the mpv render context");
        mpv_render_context_set_update_callback(s->render_context, nullptr, nullptr);
        mpv_render_context_free(s->render_context);
        s->render_context = nullptr;
    }
    s->gl->destroy();
    delete s->gl;
    s->gl = nullptr;
    LOGI("OpenGL fallback teardown: WGL context and window destroyed");
    if (attached) jvm_->DetachCurrentThread();
}

bool mpv_handle_t::apply_config_win_gl_locked() {
    auto *s = win_gl_;
    const int width = s->pending_width, height = s->pending_height;
    if (width <= 0 || height <= 0) {
        if (s->fbo) gl::delete_framebuffers(1, &s->fbo);
        if (s->texture) glDeleteTextures(1, &s->texture);
        s->fbo = 0;
        s->texture = 0;
        s->width = s->height = 0;
        s->has_frame = false;
        s->latest_width = s->latest_height = 0;
        // An inactive surface holds no frame; actually return the buffer memory
        // (clear() would keep the capacity).
        std::vector<uint8_t>().swap(s->scratch);
        std::vector<uint8_t>().swap(s->latest);
        ++s->generation;
        publish_state_win_gl_locked();
        return false;
    }
    if (s->fbo && width == s->width && height == s->height) return false;
    if (s->fbo) gl::delete_framebuffers(1, &s->fbo);
    if (s->texture) glDeleteTextures(1, &s->texture);
    s->fbo = 0;
    s->texture = 0;

    drain_gl_errors();
    glGenTextures(1, &s->texture);
    glBindTexture(GL_TEXTURE_2D, s->texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glBindTexture(GL_TEXTURE_2D, 0);
    gl::gen_framebuffers(1, &s->fbo);
    gl::bind_framebuffer(GL_FRAMEBUFFER, s->fbo);
    gl::framebuffer_texture_2d(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s->texture, 0);
    const GLenum status = gl::check_framebuffer_status(GL_FRAMEBUFFER);
    gl::bind_framebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE || glGetError() != GL_NO_ERROR) {
        LOGE("OpenGL fallback FBO incomplete (%dx%d): 0x%x", width, height, status);
        if (s->fbo) gl::delete_framebuffers(1, &s->fbo);
        if (s->texture) glDeleteTextures(1, &s->texture);
        s->fbo = 0;
        s->texture = 0;
        s->width = s->height = 0;
        s->has_frame = false;
        s->latest_width = s->latest_height = 0;
        ++s->generation;
        publish_state_win_gl_locked();
        return false;
    }

    s->width = width;
    s->height = height;
    // No frame exists at the new size yet; consumers keep drawing their cached image
    // (the previous size, letterboxed) until the render below publishes one.
    s->has_frame = false;
    ++s->generation;
    publish_state_win_gl_locked();
    LOGI("OpenGL fallback target allocated %dx%d generation=%u", width, height, s->generation);
    return true;
}

void mpv_handle_t::publish_state_win_gl_locked() {
    auto *s = win_gl_;
    const uint64_t index = s->has_frame ? 0ull : 0xFull;
    s->frame_state.store(
        (static_cast<uint64_t>(s->generation & 0xFFFFu) << 48) |
        (index << 44) |
        (static_cast<uint64_t>(s->latest_width & 0x3FFF) << 30) |
        (static_cast<uint64_t>(s->latest_height & 0x3FFF) << 16) |
        (s->serial & 0xFFFFu), std::memory_order_release);
}

bool mpv_handle_t::render_frame_win_gl(int width, int height) {
    auto *s = win_gl_;
    if (!s->render_context || !s->fbo) return false;
    mpv_opengl_fbo fbo{static_cast<int>(s->fbo), width, height, 0};
    // Same orientation contract as the GLX path: flip_y=1, and the readback flips row
    // order back to top-down (glReadPixels returns bottom-up rows).
    int flip_y = 1;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
        {MPV_RENDER_PARAM_FLIP_Y, &flip_y},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    const int result = mpv_render_context_render(s->render_context, params);
    // Failures below must be ours, not a stale error mpv's renderer left queued.
    drain_gl_errors();
    gl::bind_framebuffer(GL_FRAMEBUFFER, s->fbo);
    // mpv does not promise useful alpha for opaque video; the consumer treats the
    // frame as opaque, but normalize anyway so the CPU copies and debug PNGs are too.
    glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_TRUE);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    // Only the render thread touches scratch; the swap into latest happens under the
    // state mutex after this returns. glReadPixels is the synchronization point with
    // the GPU (no glFinish needed).
    s->scratch.resize(static_cast<size_t>(width) * height * 4);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, s->scratch.data());
    gl::bind_framebuffer(GL_FRAMEBUFFER, 0);
    return result >= 0 && glGetError() == GL_NO_ERROR;
}

void mpv_handle_t::drain_one_frame_win_gl() {
    auto *s = win_gl_;
    if (!s->render_context) return;
    mpv_render_context_update(s->render_context);
    int skip = 1;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_SKIP_RENDERING, &skip},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    mpv_render_context_render(s->render_context, params);
}

uint64_t mpv_handle_t::copy_latest_frame_win_gl(void *dest, int width, int height) {
    auto *s = win_gl_;
    if (!s || !dest || width <= 0 || height <= 0) return 0;
    std::lock_guard<std::mutex> lock(s->mutex);
    if (!s->has_frame || s->latest_width != width || s->latest_height != height) return 0;
    const size_t stride = static_cast<size_t>(width) * 4;
    if (s->latest.size() < stride * static_cast<size_t>(height)) return 0;
    auto *out = static_cast<uint8_t *>(dest);
    for (int y = 0; y < height; ++y) {
        std::memcpy(
            out + static_cast<size_t>(y) * stride,
            s->latest.data() + static_cast<size_t>(height - 1 - y) * stride,
            stride);
    }
    return s->frame_state.load(std::memory_order_relaxed);
}

bool mpv_handle_t::read_surface_pixels_win_gl(
    std::vector<uint32_t> &out_pixels, int &out_width, int &out_height) {
    auto *s = win_gl_;
    if (!s) return false;
    std::lock_guard<std::mutex> lock(s->mutex);
    if (!s->has_frame) return false;
    const int width = s->latest_width, height = s->latest_height;
    const size_t pixel_count = static_cast<size_t>(width) * height;
    if (width <= 0 || height <= 0 || s->latest.size() < pixel_count * 4) return false;
    out_pixels.resize(pixel_count);
    for (int y = 0; y < height; ++y) {
        const size_t source_row = static_cast<size_t>(height - 1 - y) * width;
        const size_t target_row = static_cast<size_t>(y) * width;
        for (int x = 0; x < width; ++x) {
            const size_t source = (source_row + x) * 4;
            out_pixels[target_row + x] = 0xFF000000u |
                (static_cast<uint32_t>(s->latest[source]) << 16) |
                (static_cast<uint32_t>(s->latest[source + 1]) << 8) |
                static_cast<uint32_t>(s->latest[source + 2]);
        }
    }
    out_width = width;
    out_height = height;
    return true;
}

bool mpv_handle_t::save_surface_png_win_gl(const char *path) {
    if (!path) return false;
    std::vector<uint32_t> pixels;
    int width = 0, height = 0;
    if (!read_surface_pixels_win_gl(pixels, width, height)) return false;
    const bool ok = write_argb_png_wic(path, width, height, pixels);
    if (!ok) LOGE("save_surface_png(OpenGL fallback) failed for %s", path);
    return ok;
}

} // namespace mediampv

#endif // _WIN32
