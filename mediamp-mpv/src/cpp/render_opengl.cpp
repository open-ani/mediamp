/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

/*
 * Desktop OpenGL producer path (GLX on Linux, WGL on Windows). Context B is created in
 * the share group of Skiko's context A. B exclusively owns libmpv's OpenGL render
 * context, the producer FBOs, and all texture allocation/deletion. Only the RGBA8 texture
 * names cross the share-group boundary; consumer FBOs belong to Skiko's context and are
 * created there (jni.cpp), not here.
 *
 * Everything except the context provider is platform-independent, so both hosts run the
 * same ring, render thread and readback code. On Windows this path coexists with the
 * D3D11 one (render_d3d11.cpp) in the same binary: which one a player uses follows the
 * Skiko render API in use.
 */

#include "gl_context_provider.h"

#ifdef MEDIAMP_OPENGL_RENDER

#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_gl.h>

#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "gl_functions.h"
#include "log.h"
#include "mpv_handle_t.h"
#include "png_writer.h"

namespace {

void *gl_get_proc_address(void *ctx, const char *name) {
    auto *provider = static_cast<mediampv::gl_context_provider *>(ctx);
    return provider ? provider->get_proc_address(name) : nullptr;
}

} // namespace

namespace mediampv {

/**
 * Producer-side state of the OpenGL render path. Held behind a pointer by mpv_handle_t so
 * the Windows build can compile it next to the D3D11 members without name collisions.
 *
 * All GL work happens on the render thread, which owns context B for its whole lifetime;
 * consumers only read the packed frame state and sample the latest texture.
 */
struct mpv_handle_t::opengl_render_state final {
    explicit opengl_render_state(mpv_handle_t *owner) : owner(owner) {}

    ~opengl_render_state() { cleanup(); }

    opengl_render_state(const opengl_render_state &) = delete;
    opengl_render_state &operator=(const opengl_render_state &) = delete;

    mpv_handle_t *const owner;

    mpv_render_context *render_context = nullptr;
    gl_context_provider *provider = nullptr;

    // Textures are share-group objects; these FBOs are context-B-local producer targets.
    static constexpr int kBufferCount = 3;
    struct opengl_buffer {
        uint32_t texture = 0; // GL_TEXTURE_2D / GL_RGBA8
        uint32_t fbo = 0;
    };
    opengl_buffer buffers[kBufferCount];
    opengl_buffer retired_buffers[kBufferCount];
    bool has_retired_buffers = false;
    bool buffers_allocated = false;
    int buffer_width = 0, buffer_height = 0;
    uint32_t buffer_generation = 0;
    uint64_t frame_serial = 0;
    int latest_index = -1;
    std::atomic<uint64_t> frame_state{0xFull << 44};

    int64_t pending_native_display = 0;
    int64_t pending_share_context = 0;
    int pending_screen = 0;
    uint64_t pending_environment_identity = 0;
    bool environment_attached = false;
    bool config_pending = false;
    int pending_width = 0, pending_height = 0;
    bool retire_ack_pending = false;
    bool render_pending = false;
    bool render_quit = false;
    bool render_initialized = false;
    bool render_initialize_ok = false;
    std::string screenshot_path;
    bool screenshot_pending = false;
    bool screenshot_finished = false;
    bool screenshot_ok = false;
    bool readback_pending = false;
    bool readback_finished = false;
    bool readback_ok = false;
    std::vector<uint32_t> readback_pixels;
    int readback_width = 0;
    int readback_height = 0;
    std::mutex render_mutex;
    std::condition_variable render_cv;
    std::thread *render_thread = nullptr;

    static void on_update(void *context) {
        // The render thread consumes the update and calls notify_render_update() only
        // after the frame is actually in a shared texture, so consumers never wake up to
        // a stale buffer.
        if (auto *state = static_cast<opengl_render_state *>(context)) state->signal_render_update();
    }

    bool attach_environment(
        int64_t native_display, int64_t share_context, int screen, uint64_t identity);
    bool create_render_context();
    bool set_surface_config(int width, int height);
    int64_t buffer_texture(int index);
    bool ack_retired();
    bool has_surface();
    bool save_png(const char *path);
    bool read_pixels(std::vector<uint32_t> &out_pixels, int &out_width, int &out_height);
    void cleanup();

    void signal_render_update();
    void start_render_thread();
    void stop_render_thread();
    void render_thread_loop();
    bool create_mpv_render_context_on_render_thread();
    void destroy_mpv_render_context_on_render_thread();
    void destroy_provider_on_render_thread();
    bool apply_config_locked();
    bool allocate_buffer(opengl_buffer &buffer, int width, int height);
    void destroy_buffer_ring(opengl_buffer *ring);
    void publish_state_locked();
    bool render_into(const opengl_buffer &buffer);
    void drain_one_frame();
    bool read_pixels_on_render_thread(
        std::vector<uint32_t> &out_pixels, int &out_width, int &out_height);
};

bool mpv_handle_t::opengl_render_state::attach_environment(
    int64_t native_display, int64_t share_context, int screen, uint64_t identity) {
    {
        std::lock_guard<std::mutex> lock(render_mutex);
        const bool same_environment = environment_attached &&
            pending_native_display == native_display &&
            pending_share_context == share_context &&
            pending_screen == screen && pending_environment_identity == identity;
        if (same_environment) return true;
    }

    // A texture name from an old share group is invalid in the new Skiko context.
    // Stop B before replacing its borrowed A handles; config dimensions are retained
    // and replayed after the new mpv context exists.
    cleanup();
    {
        std::lock_guard<std::mutex> lock(render_mutex);
        pending_native_display = native_display;
        pending_share_context = share_context;
        pending_screen = screen;
        pending_environment_identity = identity;
        environment_attached = true;
    }
    return create_render_context();
}

bool mpv_handle_t::opengl_render_state::create_render_context() {
    if (!owner->handle_) {
        LOG(owner, LOG_LEVEL_ERROR, "create_render_context(OpenGL): mpv handle is null");
        return false;
    }
    if (render_thread) return true;
    {
        std::lock_guard<std::mutex> lock(render_mutex);
        if (!environment_attached) {
            LOG(owner, LOG_LEVEL_ERROR,
                "create_render_context(OpenGL) requires a live Skiko GL environment attachment");
            return false;
        }
    }

    start_render_thread();
    std::unique_lock<std::mutex> lock(render_mutex);
    render_cv.wait(lock, [this] { return render_initialized; });
    const bool initialized = render_initialize_ok;
    lock.unlock();
    if (!initialized) cleanup();
    return initialized;
}

bool mpv_handle_t::opengl_render_state::set_surface_config(int width, int height) {
    if (!render_thread) return false;
    {
        std::lock_guard<std::mutex> lock(render_mutex);
        pending_width = width;
        pending_height = height;
        config_pending = true; // newest request replaces an unprocessed resize
    }
    render_cv.notify_all();
    return true;
}

int64_t mpv_handle_t::opengl_render_state::buffer_texture(int index) {
    std::lock_guard<std::mutex> lock(render_mutex);
    if (!buffers_allocated || index < 0 || index >= kBufferCount) return 0;
    return static_cast<int64_t>(buffers[index].texture);
}

bool mpv_handle_t::opengl_render_state::ack_retired() {
    {
        std::lock_guard<std::mutex> lock(render_mutex);
        retire_ack_pending = true;
    }
    render_cv.notify_all();
    return true;
}

bool mpv_handle_t::opengl_render_state::has_surface() {
    std::lock_guard<std::mutex> lock(render_mutex);
    return buffers_allocated;
}

void mpv_handle_t::opengl_render_state::signal_render_update() {
    {
        std::lock_guard<std::mutex> lock(render_mutex);
        render_pending = true;
    }
    render_cv.notify_all();
}

void mpv_handle_t::opengl_render_state::start_render_thread() {
    if (render_thread) return;
    {
        std::lock_guard<std::mutex> lock(render_mutex);
        render_quit = false;
        render_initialized = false;
        render_initialize_ok = false;
    }
    render_thread = new std::thread([this] { render_thread_loop(); });
}

void mpv_handle_t::opengl_render_state::stop_render_thread() {
    if (!render_thread) return;
    {
        std::lock_guard<std::mutex> lock(render_mutex);
        render_quit = true;
    }
    render_cv.notify_all();
    if (render_thread->joinable()) render_thread->join();
    delete render_thread;
    render_thread = nullptr;
}

bool mpv_handle_t::opengl_render_state::create_mpv_render_context_on_render_thread() {
    mpv_opengl_init_params gl_init_params{
        .get_proc_address = gl_get_proc_address,
        .get_proc_address_ctx = provider,
    };
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init_params},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    const int result = mpv_render_context_create(&render_context, owner->handle_, params);
    if (result < 0) {
        render_context = nullptr;
        LOG(owner, LOG_LEVEL_ERROR,
            "mpv_render_context_create(OpenGL) failed: %s", mpv_error_string(result));
        return false;
    }
    LOG(owner, LOG_LEVEL_INFO, "mpv GL producer: %s / %s",
        reinterpret_cast<const char *>(glGetString(GL_VERSION)),
        reinterpret_cast<const char *>(glGetString(GL_RENDERER)));
    mpv_render_context_set_update_callback(render_context, &opengl_render_state::on_update, this);
    return true;
}

void mpv_handle_t::opengl_render_state::destroy_mpv_render_context_on_render_thread() {
    if (!render_context) return;
    mpv_render_context_set_update_callback(render_context, nullptr, nullptr);
    mpv_render_context_free(render_context);
    render_context = nullptr;
}

void mpv_handle_t::opengl_render_state::destroy_provider_on_render_thread() {
    if (!provider) return;
    if (!provider->destroy()) {
        LOG(owner, LOG_LEVEL_ERROR,
            "GL producer teardown failed: %s", provider->last_error().c_str());
    }
    delete provider;
    provider = nullptr;
}

void mpv_handle_t::opengl_render_state::render_thread_loop() {
    JNIEnv *thread_env = nullptr;
    const bool attached = owner->jvm_ &&
        owner->jvm_->AttachCurrentThread(reinterpret_cast<void **>(&thread_env), nullptr) == JNI_OK;

    gl_render_environment environment;
    {
        std::lock_guard<std::mutex> lock(render_mutex);
        environment.native_display = pending_native_display;
        environment.share_context = pending_share_context;
        environment.screen = pending_screen;
        environment.identity = pending_environment_identity;
    }
    // Context B is created here rather than by the caller: it must be owned by the thread
    // that makes it current, and the WGL implementation bootstraps its entry points
    // through a throwaway context that would displace Skiko's on the UI thread.
    std::string error;
    provider = create_gl_context_provider(environment, &error);
    if (!provider) {
        LOG(owner, LOG_LEVEL_ERROR, "cannot create shared GL producer context: %s", error.c_str());
    }
    const bool context_current = provider && provider->make_current();
    const bool initialized = context_current && create_mpv_render_context_on_render_thread();
    {
        std::lock_guard<std::mutex> lock(render_mutex);
        render_initialize_ok = initialized;
        render_initialized = true;
    }
    render_cv.notify_all();
    if (!initialized) {
        if (context_current) provider->clear_current();
        destroy_provider_on_render_thread();
        if (attached) owner->jvm_->DetachCurrentThread();
        return;
    }

    std::unique_lock<std::mutex> lock(render_mutex);
    while (!render_quit) {
        render_cv.wait(lock, [this] {
            return render_quit || render_pending || config_pending || retire_ack_pending ||
                screenshot_pending || readback_pending;
        });
        if (render_quit) break;

        if (retire_ack_pending) {
            retire_ack_pending = false;
            if (has_retired_buffers) {
                destroy_buffer_ring(retired_buffers);
                has_retired_buffers = false;
            }
        }
        bool configured = false;
        // Exactly one retired generation is retained. Keep coalescing rapid resize
        // requests until the consumer releases it; never overwrite live GL textures.
        if (config_pending && !has_retired_buffers) {
            config_pending = false;
            configured = apply_config_locked();
        }
        if (screenshot_pending) {
            const std::string path = screenshot_path;
            screenshot_pending = false;
            lock.unlock();
            std::vector<uint32_t> pixels;
            int width = 0, height = 0;
            const bool saved = read_pixels_on_render_thread(pixels, width, height) &&
                write_argb_png(path.c_str(), width, height, pixels.data());
            lock.lock();
            screenshot_ok = saved;
            screenshot_finished = true;
            render_cv.notify_all();
            continue;
        }
        if (readback_pending) {
            readback_pending = false;
            lock.unlock();
            std::vector<uint32_t> pixels;
            int width = 0, height = 0;
            const bool read = read_pixels_on_render_thread(pixels, width, height);
            lock.lock();
            readback_pixels = std::move(pixels);
            readback_width = width;
            readback_height = height;
            readback_ok = read;
            readback_finished = true;
            render_cv.notify_all();
            continue;
        }
        const bool want_render = render_pending;
        render_pending = false;
        if (!buffers_allocated) {
            if (want_render) {
                lock.unlock();
                drain_one_frame();
                lock.lock();
            }
            continue;
        }
        bool has_new_frame = false;
        if (want_render) {
            has_new_frame = (mpv_render_context_update(render_context) & MPV_RENDER_UPDATE_FRAME) != 0;
        }
        if (!has_new_frame && !configured) continue;
        const int next = (latest_index + 1) % kBufferCount;
        const opengl_buffer target = buffers[next];
        lock.unlock();
        const bool rendered = render_into(target);
        lock.lock();
        if (rendered) {
            latest_index = next;
            ++frame_serial;
            publish_state_locked();
            lock.unlock();
            owner->notify_render_update(); // release-store completed before this JNI callback
            lock.lock();
        }
    }
    // Teardown must run in B's owner thread while B is current.
    if (has_retired_buffers) destroy_buffer_ring(retired_buffers);
    if (buffers_allocated) destroy_buffer_ring(buffers);
    has_retired_buffers = false;
    buffers_allocated = false;
    latest_index = -1;
    buffer_width = buffer_height = 0;
    ++buffer_generation;
    publish_state_locked();
    destroy_mpv_render_context_on_render_thread();
    lock.unlock();
    provider->clear_current();
    destroy_provider_on_render_thread();
    if (attached) owner->jvm_->DetachCurrentThread();
}

bool mpv_handle_t::opengl_render_state::apply_config_locked() {
    const int width = pending_width, height = pending_height;
    if (width <= 0 || height <= 0) {
        // releaseSurface() first drops consumer FBO/surface references. This explicit
        // deactivation is therefore allowed to discard both generations immediately.
        if (has_retired_buffers) destroy_buffer_ring(retired_buffers);
        if (buffers_allocated) destroy_buffer_ring(buffers);
        has_retired_buffers = buffers_allocated = false;
        latest_index = -1;
        buffer_width = buffer_height = 0;
        ++buffer_generation;
        publish_state_locked();
        return false;
    }
    if (buffers_allocated && width == buffer_width && height == buffer_height) return false;
    if (buffers_allocated) {
        for (int i = 0; i < kBufferCount; ++i) {
            retired_buffers[i] = buffers[i];
            buffers[i] = opengl_buffer{};
        }
        has_retired_buffers = true;
        buffers_allocated = false;
    }
    bool ok = true;
    for (auto &buffer : buffers) ok = ok && allocate_buffer(buffer, width, height);
    if (!ok) {
        LOG(owner, LOG_LEVEL_ERROR, "OpenGL producer ring allocation failed (%dx%d)", width, height);
        destroy_buffer_ring(buffers);
        latest_index = -1;
        buffer_width = buffer_height = 0;
        ++buffer_generation;
        publish_state_locked();
        return false;
    }
    buffers_allocated = true;
    buffer_width = width;
    buffer_height = height;
    latest_index = -1;
    ++buffer_generation;
    publish_state_locked();
    LOG(owner, LOG_LEVEL_INFO, "OpenGL producer ring allocated %dx%d generation=%u",
        width, height, buffer_generation);
    return true;
}

bool mpv_handle_t::opengl_render_state::allocate_buffer(
    opengl_buffer &buffer, int width, int height) {
    glGenTextures(1, &buffer.texture);
    glBindTexture(GL_TEXTURE_2D, buffer.texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glBindTexture(GL_TEXTURE_2D, 0);
    glGenFramebuffers(1, &buffer.fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, buffer.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, buffer.texture, 0);
    const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOG(owner, LOG_LEVEL_ERROR, "OpenGL producer FBO incomplete: 0x%x", status);
        if (buffer.fbo) glDeleteFramebuffers(1, &buffer.fbo);
        if (buffer.texture) glDeleteTextures(1, &buffer.texture);
        buffer = opengl_buffer{};
        return false;
    }
    return glGetError() == GL_NO_ERROR;
}

void mpv_handle_t::opengl_render_state::destroy_buffer_ring(opengl_buffer *ring) {
    for (int i = 0; i < kBufferCount; ++i) {
        if (ring[i].fbo) glDeleteFramebuffers(1, &ring[i].fbo);
        if (ring[i].texture) glDeleteTextures(1, &ring[i].texture);
        ring[i] = opengl_buffer{};
    }
}

void mpv_handle_t::opengl_render_state::publish_state_locked() {
    const uint64_t index = latest_index < 0 ? 0xFULL : static_cast<uint64_t>(latest_index);
    frame_state.store(
        (static_cast<uint64_t>(buffer_generation & 0xFFFFu) << 48) |
        (index << 44) |
        (static_cast<uint64_t>(buffer_width & 0x3FFF) << 30) |
        (static_cast<uint64_t>(buffer_height & 0x3FFF) << 16) |
        (frame_serial & 0xFFFFu), std::memory_order_release);
}

bool mpv_handle_t::opengl_render_state::render_into(const opengl_buffer &buffer) {
    if (!render_context || !buffer.fbo) return false;
    mpv_opengl_fbo fbo{static_cast<int>(buffer.fbo), buffer_width, buffer_height, 0};
    int flip_y = 1;
    // Keep the published texture and debug PNG top-down. The OpenGL consumer describes
    // the FBO to Skia with a bottom-left origin; changing both ends would double-flip it.
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
        {MPV_RENDER_PARAM_FLIP_Y, &flip_y},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    const int result = mpv_render_context_render(render_context, params);
    // mpv does not promise useful alpha for opaque video. RGBA_8888 Skia sampling is
    // premultiplied, so normalize alpha before this texture is made visible to A.
    glBindFramebuffer(GL_FRAMEBUFFER, buffer.fbo);
    glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_TRUE);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    // glFlush merely submits work. Publication after glFinish is the first-version
    // producer completion protocol and prevents Skia from sampling partial writes.
    glFinish();
    return result >= 0 && glGetError() == GL_NO_ERROR;
}

void mpv_handle_t::opengl_render_state::drain_one_frame() {
    if (!render_context) return;
    mpv_render_context_update(render_context);
    int skip = 1;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_SKIP_RENDERING, &skip},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    mpv_render_context_render(render_context, params);
}

bool mpv_handle_t::opengl_render_state::read_pixels_on_render_thread(
    std::vector<uint32_t> &out_pixels, int &out_width, int &out_height) {
    if (!buffers_allocated || latest_index < 0) return false;
    const opengl_buffer &buffer = buffers[latest_index];
    const int width = buffer_width, height = buffer_height;
    const size_t pixel_count = static_cast<size_t>(width) * height;
    std::vector<uint8_t> rgba(pixel_count * 4);
    glBindFramebuffer(GL_FRAMEBUFFER, buffer.fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (glGetError() != GL_NO_ERROR) return false;

    // glReadPixels is bottom-up; the published contract is top-down ARGB with opaque alpha.
    out_pixels.resize(pixel_count);
    for (int y = 0; y < height; ++y) {
        const size_t source_row = static_cast<size_t>(height - 1 - y) * width;
        const size_t target_row = static_cast<size_t>(y) * width;
        for (int x = 0; x < width; ++x) {
            const size_t source = (source_row + x) * 4;
            out_pixels[target_row + x] = 0xFF000000u |
                (static_cast<uint32_t>(rgba[source]) << 16) |
                (static_cast<uint32_t>(rgba[source + 1]) << 8) |
                static_cast<uint32_t>(rgba[source + 2]);
        }
    }
    out_width = width;
    out_height = height;
    return true;
}

bool mpv_handle_t::opengl_render_state::save_png(const char *path) {
    if (!path || !render_thread) return false;
    std::unique_lock<std::mutex> lock(render_mutex);
    if (!buffers_allocated || latest_index < 0 || screenshot_pending) return false;
    screenshot_path = path;
    screenshot_pending = true;
    screenshot_finished = false;
    render_cv.notify_all();
    render_cv.wait(lock, [this] { return screenshot_finished || render_quit; });
    return screenshot_finished && screenshot_ok;
}

bool mpv_handle_t::opengl_render_state::read_pixels(
    std::vector<uint32_t> &out_pixels, int &out_width, int &out_height) {
    if (!render_thread) return false;
    std::unique_lock<std::mutex> lock(render_mutex);
    if (!buffers_allocated || latest_index < 0 || readback_pending) return false;
    readback_pixels.clear();
    readback_width = readback_height = 0;
    readback_pending = true;
    readback_finished = false;
    readback_ok = false;
    render_cv.notify_all();
    render_cv.wait(lock, [this] { return readback_finished || render_quit; });
    if (!readback_finished || !readback_ok) return false;
    out_pixels = readback_pixels;
    out_width = readback_width;
    out_height = readback_height;
    return true;
}

void mpv_handle_t::opengl_render_state::cleanup() {
    // The render thread frees the mpv render context, the ring and context B itself, in
    // that order, while B is still current on it.
    stop_render_thread();
    std::lock_guard<std::mutex> lock(render_mutex);
    render_context = nullptr;
    has_retired_buffers = buffers_allocated = false;
    latest_index = -1;
    buffer_width = buffer_height = 0;
    ++buffer_generation;
    publish_state_locked();
}

// ---- mpv_handle_t entry points ----

mpv_handle_t::opengl_render_state *mpv_handle_t::ensure_opengl_state() {
    if (!opengl_state_) opengl_state_ = new opengl_render_state(this);
    return opengl_state_;
}

void mpv_handle_t::cleanup_opengl_render_resources() {
    if (opengl_state_) opengl_state_->cleanup();
}

void mpv_handle_t::destroy_opengl_render_state() {
    delete opengl_state_; // its destructor stops the render thread
    opengl_state_ = nullptr;
}

bool mpv_handle_t::attach_opengl_render_environment(
    int64_t native_display, int64_t share_context, int screen, uint64_t identity) {
    if (!native_display || !share_context || identity == 0) {
        LOG(this, LOG_LEVEL_ERROR,
            "attach_opengl_render_environment requires a non-zero display, GL context, and identity");
        return false;
    }
    if (!handle_) {
        LOG(this, LOG_LEVEL_ERROR, "attach_opengl_render_environment: mpv handle is null");
        return false;
    }
    return ensure_opengl_state()->attach_environment(
        native_display, share_context, screen, identity);
}

bool mpv_handle_t::create_opengl_render_context() {
    if (!handle_) {
        LOG(this, LOG_LEVEL_ERROR, "create_opengl_render_context: mpv handle is null");
        return false;
    }
    return ensure_opengl_state()->create_render_context();
}

bool mpv_handle_t::destroy_opengl_render_context() {
    cleanup_opengl_render_resources();
    return true;
}

bool mpv_handle_t::set_opengl_surface_config(int width, int height) {
    return opengl_state_ && opengl_state_->set_surface_config(width, height);
}

uint64_t mpv_handle_t::get_opengl_frame_state() {
    if (!opengl_state_) return 0xFull << 44; // "no buffer" sentinel
    return opengl_state_->frame_state.load(std::memory_order_acquire);
}

int64_t mpv_handle_t::get_opengl_buffer_texture(int index) {
    return opengl_state_ ? opengl_state_->buffer_texture(index) : 0;
}

bool mpv_handle_t::ack_opengl_retired_buffers() {
    return opengl_state_ && opengl_state_->ack_retired();
}

bool mpv_handle_t::has_opengl_surface() {
    return opengl_state_ && opengl_state_->has_surface();
}

bool mpv_handle_t::save_opengl_surface_png(const char *path) {
    return opengl_state_ && opengl_state_->save_png(path);
}

bool mpv_handle_t::read_opengl_surface_pixels(
    std::vector<uint32_t> &out_pixels, int &out_width, int &out_height) {
    return opengl_state_ && opengl_state_->read_pixels(out_pixels, out_width, out_height);
}

} // namespace mediampv

#endif // MEDIAMP_OPENGL_RENDER
