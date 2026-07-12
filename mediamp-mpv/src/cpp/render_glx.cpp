/*
 * Linux OpenGL producer path. Context B is a GLX context in Skiko context A's
 * share group. B exclusively owns libmpv's OpenGL render context, the producer
 * FBOs, and all texture allocation/deletion. Only the RGBA8 texture names cross
 * the share-group boundary; consumer FBOs belong to Skiko's context and are not
 * created here.
 */

#if defined(__linux__) && !defined(__ANDROID__)

#define GL_GLEXT_PROTOTYPES 1
#include <GL/gl.h>
#include <GL/glext.h>
#include <GL/glx.h>

#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_gl.h>
#include <zlib.h>

#include <array>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <thread>
#include <vector>

#include "glx_context_provider.h"
#include "log.h"
#include "mpv_handle_t.h"

namespace {

void *glx_get_proc_address(void *ctx, const char *name) {
    auto *provider = static_cast<mediampv::glx_context_provider *>(ctx);
    return provider ? provider->get_proc_address(name) : nullptr;
}

void append_u32_be(std::vector<uint8_t> &out, uint32_t value) {
    out.push_back(static_cast<uint8_t>(value >> 24));
    out.push_back(static_cast<uint8_t>(value >> 16));
    out.push_back(static_cast<uint8_t>(value >> 8));
    out.push_back(static_cast<uint8_t>(value));
}

void append_png_chunk(
    std::vector<uint8_t> &out, const char type[4], const uint8_t *data, size_t size) {
    append_u32_be(out, static_cast<uint32_t>(size));
    const size_t type_offset = out.size();
    out.insert(out.end(), type, type + 4);
    if (size != 0) out.insert(out.end(), data, data + size);
    const auto checksum = crc32(
        0L, reinterpret_cast<const Bytef *>(out.data() + type_offset),
        static_cast<uInt>(4 + size));
    append_u32_be(out, static_cast<uint32_t>(checksum));
}

bool write_rgba_png(const char *path, int width, int height, const std::vector<uint8_t> &bottom_up) {
    if (!path || width <= 0 || height <= 0) return false;
    const size_t stride = static_cast<size_t>(width) * 4;
    std::vector<uint8_t> scanlines(static_cast<size_t>(height) * (stride + 1));
    // glReadPixels is bottom-up; PNG scanlines are top-down.
    for (int y = 0; y < height; ++y) {
        auto *dst = scanlines.data() + static_cast<size_t>(y) * (stride + 1);
        dst[0] = 0; // PNG filter None
        const auto *src = bottom_up.data() + static_cast<size_t>(height - 1 - y) * stride;
        std::memcpy(dst + 1, src, stride);
    }
    uLongf compressed_size = compressBound(static_cast<uLong>(scanlines.size()));
    std::vector<uint8_t> compressed(compressed_size);
    if (compress2(compressed.data(), &compressed_size, scanlines.data(),
                  static_cast<uLong>(scanlines.size()), Z_BEST_SPEED) != Z_OK) {
        return false;
    }
    compressed.resize(compressed_size);

    std::vector<uint8_t> png;
    constexpr std::array<uint8_t, 8> signature = {137, 80, 78, 71, 13, 10, 26, 10};
    png.insert(png.end(), signature.begin(), signature.end());
    std::array<uint8_t, 13> ihdr{};
    ihdr[0] = static_cast<uint8_t>(width >> 24);
    ihdr[1] = static_cast<uint8_t>(width >> 16);
    ihdr[2] = static_cast<uint8_t>(width >> 8);
    ihdr[3] = static_cast<uint8_t>(width);
    ihdr[4] = static_cast<uint8_t>(height >> 24);
    ihdr[5] = static_cast<uint8_t>(height >> 16);
    ihdr[6] = static_cast<uint8_t>(height >> 8);
    ihdr[7] = static_cast<uint8_t>(height);
    ihdr[8] = 8;  // bits/component
    ihdr[9] = 6;  // RGBA
    append_png_chunk(png, "IHDR", ihdr.data(), ihdr.size());
    append_png_chunk(png, "IDAT", compressed.data(), compressed.size());
    append_png_chunk(png, "IEND", nullptr, 0);

    FILE *file = std::fopen(path, "wb");
    if (!file) return false;
    const bool ok = std::fwrite(png.data(), 1, png.size(), file) == png.size();
    std::fclose(file);
    return ok;
}

} // namespace

namespace mediampv {

bool mpv_handle_t::attach_opengl_render_environment(
    int64_t display_ptr, int64_t share_context_ptr, int screen, uint64_t identity) {
    if (!display_ptr || !share_context_ptr || identity == 0) {
        LOGE("attach_opengl_render_environment requires non-zero display, GLX context, and identity");
        return false;
    }
    bool same_environment = false;
    {
        std::lock_guard<std::mutex> lock(render_mutex_);
        same_environment = environment_attached_ &&
            pending_display_ptr_ == display_ptr &&
            pending_share_context_ptr_ == share_context_ptr &&
            pending_screen_ == screen && pending_environment_identity_ == identity;
        if (same_environment) return true;
    }

    // A texture name from an old share group is invalid in the new Skiko context.
    // Stop B before replacing its borrowed A pointers; config dimensions are retained
    // and replayed after the new mpv context exists.
    cleanup_render_resources();
    {
        std::lock_guard<std::mutex> lock(render_mutex_);
        pending_display_ptr_ = display_ptr;
        pending_share_context_ptr_ = share_context_ptr;
        pending_screen_ = screen;
        pending_environment_identity_ = identity;
        environment_attached_ = true;
    }
    return create_render_context();
}

bool mpv_handle_t::create_render_context() {
    if (!handle_) {
        LOGE("create_render_context(OpenGL): mpv handle is null");
        return false;
    }
    if (render_thread_) return true;

    glx_render_environment environment;
    {
        std::lock_guard<std::mutex> lock(render_mutex_);
        if (!environment_attached_) {
            LOGE("create_render_context(OpenGL) requires a live Skiko GLX environment attachment");
            return false;
        }
        environment.display = reinterpret_cast<Display *>(static_cast<uintptr_t>(pending_display_ptr_));
        environment.share_context = reinterpret_cast<GLXContext>(static_cast<uintptr_t>(pending_share_context_ptr_));
        environment.screen = pending_screen_;
        environment.identity = pending_environment_identity_;
    }
    std::string error;
    glx_provider_ = glx_context_provider::create(environment, &error);
    if (!glx_provider_) {
        LOGE("cannot create shared GLX producer context: %s", error.c_str());
        return false;
    }
    start_render_thread();
    std::unique_lock<std::mutex> lock(render_mutex_);
    render_cv_.wait(lock, [this] { return render_initialized_; });
    const bool initialized = render_initialize_ok_;
    lock.unlock();
    if (!initialized) cleanup_render_resources();
    return initialized;
}

bool mpv_handle_t::destroy_render_context() {
    cleanup_render_resources();
    return true;
}

bool mpv_handle_t::set_surface_config(int width, int height, int64_t) {
    if (!render_thread_) return false;
    {
        std::lock_guard<std::mutex> lock(render_mutex_);
        pending_width_ = width;
        pending_height_ = height;
        config_pending_ = true; // newest request replaces an unprocessed resize
    }
    render_cv_.notify_all();
    return true;
}

uint64_t mpv_handle_t::get_frame_state() {
    return frame_state_.load(std::memory_order_acquire);
}

int64_t mpv_handle_t::get_buffer_texture(int index) {
    std::lock_guard<std::mutex> lock(render_mutex_);
    if (!buffers_allocated_ || index < 0 || index >= kOpenGLBufferCount) return 0;
    return static_cast<int64_t>(buffers_[index].texture);
}

bool mpv_handle_t::ack_retired_buffers() {
    {
        std::lock_guard<std::mutex> lock(render_mutex_);
        retire_ack_pending_ = true;
    }
    render_cv_.notify_all();
    return true;
}

bool mpv_handle_t::has_opengl_surface() {
    std::lock_guard<std::mutex> lock(render_mutex_);
    return buffers_allocated_;
}

void mpv_handle_t::signal_render_update() {
    {
        std::lock_guard<std::mutex> lock(render_mutex_);
        render_pending_ = true;
    }
    render_cv_.notify_all();
}

void mpv_handle_t::start_render_thread() {
    if (render_thread_) return;
    {
        std::lock_guard<std::mutex> lock(render_mutex_);
        render_quit_ = false;
        render_initialized_ = false;
        render_initialize_ok_ = false;
    }
    render_thread_ = new std::thread([this] { render_thread_loop(); });
}

void mpv_handle_t::stop_render_thread() {
    if (!render_thread_) return;
    {
        std::lock_guard<std::mutex> lock(render_mutex_);
        render_quit_ = true;
    }
    render_cv_.notify_all();
    auto *thread = static_cast<std::thread *>(render_thread_);
    if (thread->joinable()) thread->join();
    delete thread;
    render_thread_ = nullptr;
}

bool mpv_handle_t::create_mpv_render_context_on_render_thread() {
    mpv_opengl_init_params gl_init_params{
        .get_proc_address = glx_get_proc_address,
        .get_proc_address_ctx = glx_provider_,
    };
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init_params},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    const int result = mpv_render_context_create(&render_context_, handle_, params);
    if (result < 0) {
        render_context_ = nullptr;
        LOGE("mpv_render_context_create(OpenGL) failed: %s", mpv_error_string(result));
        return false;
    }
    LOGI("mpv GL producer: %s / %s", glGetString(GL_VERSION), glGetString(GL_RENDERER));
    mpv_render_context_set_update_callback(render_context_, &mpv_handle_t::on_render_update, this);
    return true;
}

void mpv_handle_t::destroy_mpv_render_context_on_render_thread() {
    if (!render_context_) return;
    mpv_render_context_set_update_callback(render_context_, nullptr, nullptr);
    mpv_render_context_free(render_context_);
    render_context_ = nullptr;
}

void mpv_handle_t::render_thread_loop() {
    JNIEnv *thread_env = nullptr;
    const bool attached = jvm_ &&
        jvm_->AttachCurrentThread(reinterpret_cast<void **>(&thread_env), nullptr) == JNI_OK;
    const bool context_current = glx_provider_ && glx_provider_->make_current();
    const bool initialized = context_current && create_mpv_render_context_on_render_thread();
    {
        std::lock_guard<std::mutex> lock(render_mutex_);
        render_initialize_ok_ = initialized;
        render_initialized_ = true;
    }
    render_cv_.notify_all();
    if (!initialized) {
        if (context_current) glx_provider_->clear_current();
        if (attached) jvm_->DetachCurrentThread();
        return;
    }

    std::unique_lock<std::mutex> lock(render_mutex_);
    while (!render_quit_) {
        render_cv_.wait(lock, [this] {
            return render_quit_ || render_pending_ || config_pending_ || retire_ack_pending_ ||
                screenshot_pending_;
        });
        if (render_quit_) break;

        if (retire_ack_pending_) {
            retire_ack_pending_ = false;
            if (has_retired_buffers_) {
                destroy_buffer_ring(retired_buffers_);
                has_retired_buffers_ = false;
            }
        }
        bool configured = false;
        // Exactly one retired generation is retained. Keep coalescing rapid resize
        // requests until the consumer releases it; never overwrite live GL textures.
        if (config_pending_ && !has_retired_buffers_) {
            config_pending_ = false;
            configured = apply_config_locked();
        }
        if (screenshot_pending_) {
            const std::string path = screenshot_path_;
            screenshot_pending_ = false;
            lock.unlock();
            const bool saved = write_surface_png_on_render_thread(path.c_str());
            lock.lock();
            screenshot_ok_ = saved;
            screenshot_finished_ = true;
            render_cv_.notify_all();
            continue;
        }
        const bool want_render = render_pending_;
        render_pending_ = false;
        if (!buffers_allocated_) {
            if (want_render) {
                lock.unlock();
                drain_one_frame();
                lock.lock();
            }
            continue;
        }
        bool has_new_frame = false;
        if (want_render) {
            has_new_frame = (mpv_render_context_update(render_context_) & MPV_RENDER_UPDATE_FRAME) != 0;
        }
        if (!has_new_frame && !configured) continue;
        const int next = (latest_index_ + 1) % kOpenGLBufferCount;
        const opengl_buffer target = buffers_[next];
        lock.unlock();
        const bool rendered = render_into(target);
        lock.lock();
        if (rendered) {
            latest_index_ = next;
            ++frame_serial_;
            publish_state_locked();
            lock.unlock();
            notify_render_update(); // release-store has completed before this JNI callback
            lock.lock();
        }
    }
    // Teardown must run in B's owner thread while B is current.
    if (has_retired_buffers_) destroy_buffer_ring(retired_buffers_);
    if (buffers_allocated_) destroy_buffer_ring(buffers_);
    has_retired_buffers_ = false;
    buffers_allocated_ = false;
    latest_index_ = -1;
    buffer_width_ = buffer_height_ = 0;
    ++buffer_generation_;
    publish_state_locked();
    destroy_mpv_render_context_on_render_thread();
    lock.unlock();
    glx_provider_->clear_current();
    if (attached) jvm_->DetachCurrentThread();
}

bool mpv_handle_t::apply_config_locked() {
    const int width = pending_width_, height = pending_height_;
    if (width <= 0 || height <= 0) {
        // releaseSurface() first drops consumer FBO/surface references. This explicit
        // deactivation is therefore allowed to discard both generations immediately.
        if (has_retired_buffers_) destroy_buffer_ring(retired_buffers_);
        if (buffers_allocated_) destroy_buffer_ring(buffers_);
        has_retired_buffers_ = buffers_allocated_ = false;
        latest_index_ = -1;
        buffer_width_ = buffer_height_ = 0;
        ++buffer_generation_;
        publish_state_locked();
        return false;
    }
    if (buffers_allocated_ && width == buffer_width_ && height == buffer_height_) return false;
    if (buffers_allocated_) {
        for (int i = 0; i < kOpenGLBufferCount; ++i) {
            retired_buffers_[i] = buffers_[i];
            buffers_[i] = opengl_buffer{};
        }
        has_retired_buffers_ = true;
        buffers_allocated_ = false;
    }
    bool ok = true;
    for (auto &buffer : buffers_) ok = ok && allocate_buffer(buffer, width, height);
    if (!ok) {
        LOGE("OpenGL producer ring allocation failed (%dx%d)", width, height);
        destroy_buffer_ring(buffers_);
        latest_index_ = -1;
        buffer_width_ = buffer_height_ = 0;
        ++buffer_generation_;
        publish_state_locked();
        return false;
    }
    buffers_allocated_ = true;
    buffer_width_ = width;
    buffer_height_ = height;
    latest_index_ = -1;
    ++buffer_generation_;
    publish_state_locked();
    LOGI("OpenGL producer ring allocated %dx%d generation=%u", width, height, buffer_generation_);
    return true;
}

bool mpv_handle_t::allocate_buffer(opengl_buffer &buffer, int width, int height) {
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
        LOGE("OpenGL producer FBO incomplete: 0x%x", status);
        if (buffer.fbo) glDeleteFramebuffers(1, &buffer.fbo);
        if (buffer.texture) glDeleteTextures(1, &buffer.texture);
        buffer = opengl_buffer{};
        return false;
    }
    return glGetError() == GL_NO_ERROR;
}

void mpv_handle_t::destroy_buffer_ring(opengl_buffer *ring) {
    for (int i = 0; i < kOpenGLBufferCount; ++i) {
        if (ring[i].fbo) glDeleteFramebuffers(1, &ring[i].fbo);
        if (ring[i].texture) glDeleteTextures(1, &ring[i].texture);
        ring[i] = opengl_buffer{};
    }
}

void mpv_handle_t::publish_state_locked() {
    const uint64_t index = latest_index_ < 0 ? 0xFULL : static_cast<uint64_t>(latest_index_);
    frame_state_.store(
        (static_cast<uint64_t>(buffer_generation_ & 0xFFFFu) << 48) |
        (index << 44) |
        (static_cast<uint64_t>(buffer_width_ & 0x3FFF) << 30) |
        (static_cast<uint64_t>(buffer_height_ & 0x3FFF) << 16) |
        (frame_serial_ & 0xFFFFu), std::memory_order_release);
}

bool mpv_handle_t::render_into(const opengl_buffer &buffer) {
    if (!render_context_ || !buffer.fbo) return false;
    mpv_opengl_fbo fbo{static_cast<int>(buffer.fbo), buffer_width_, buffer_height_, 0};
    int flip_y = 1;
    // Keep the published texture and debug PNG top-down. The OpenGL consumer describes
    // the FBO to Skia with a bottom-left origin; changing both ends would double-flip it.
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
        {MPV_RENDER_PARAM_FLIP_Y, &flip_y},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    const int result = mpv_render_context_render(render_context_, params);
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

bool mpv_handle_t::write_surface_png_on_render_thread(const char *path) {
    if (!buffers_allocated_ || latest_index_ < 0 || !path) return false;
    const opengl_buffer &buffer = buffers_[latest_index_];
    std::vector<uint8_t> pixels(static_cast<size_t>(buffer_width_) * buffer_height_ * 4);
    glBindFramebuffer(GL_FRAMEBUFFER, buffer.fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, buffer_width_, buffer_height_, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (glGetError() != GL_NO_ERROR) return false;
    const bool ok = write_rgba_png(path, buffer_width_, buffer_height_, pixels);
    if (!ok) LOGE("save_surface_png failed for %s", path);
    return ok;
}

bool mpv_handle_t::save_surface_png(const char *path) {
    if (!path || !render_thread_) return false;
    std::unique_lock<std::mutex> lock(render_mutex_);
    if (!buffers_allocated_ || latest_index_ < 0 || screenshot_pending_) return false;
    screenshot_path_ = path;
    screenshot_pending_ = true;
    screenshot_finished_ = false;
    render_cv_.notify_all();
    render_cv_.wait(lock, [this] { return screenshot_finished_ || render_quit_; });
    return screenshot_finished_ && screenshot_ok_;
}

void mpv_handle_t::cleanup_render_resources() {
    stop_render_thread();
    if (glx_provider_) {
        if (!glx_provider_->destroy()) LOGE("GLX producer teardown failed: %s", glx_provider_->last_error().c_str());
        delete glx_provider_;
        glx_provider_ = nullptr;
    }
    std::lock_guard<std::mutex> lock(render_mutex_);
    render_context_ = nullptr;
    has_retired_buffers_ = buffers_allocated_ = false;
    latest_index_ = -1;
    buffer_width_ = buffer_height_ = 0;
    ++buffer_generation_;
    publish_state_locked();
}

} // namespace mediampv

#endif // defined(__linux__) && !defined(__ANDROID__)
