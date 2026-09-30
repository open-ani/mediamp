/*
 * Linux OpenGL producer path. Context B is a GLX context in Skiko context A's
 * share group. B exclusively owns libmpv's OpenGL render context, the producer
 * FBOs, and all texture allocation/deletion. Only the RGBA8 texture names cross
 * the share-group boundary; consumer FBOs belong to Skiko's context and are not
 * created here.
 */

#include "platform.h"
#ifdef MEDIAMPV_LINUX_DESKTOP

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
#include "surface_ring.h"

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

namespace {

// Textures are share-group objects; these FBOs are context-B-local producer targets.
struct opengl_buffer {
    uint32_t texture = 0; // GL_TEXTURE_2D / GL_RGBA8
    uint32_t fbo = 0;
};

class glx_renderer final : public surface_ring<opengl_buffer> {
public:
    explicit glx_renderer(mpv_handle_t &owner) : surface_ring(owner, "OpenGL/GLX") {}
    ~glx_renderer() override { shutdown(); }

    bool create(const glx_environment_ref &environment);

    bool save_surface_png(const char *path) override;
    bool read_surface_pixels(std::vector<uint32_t> &pixels, int &width, int &height) override;

protected:
    // B must be current on its sole render thread, and libmpv's GL render context is
    // created, used and freed there.
    bool on_thread_start() override;
    void on_thread_exit() override;
    void after_shutdown() override;
    bool allocate_buffer(opengl_buffer &buffer, int width, int height) override;
    void destroy_buffer(opengl_buffer &buffer) override;
    int64_t texture_handle(const opengl_buffer &buffer) const override {
        return static_cast<int64_t>(buffer.texture); // GLuint texture name, not an FBO
    }
    bool render_into(const opengl_buffer &buffer) override;
    // Screenshots and pixel readbacks need B's context, so they run on the render thread.
    bool has_requests_locked() const override { return screenshot_pending_ || readback_pending_; }
    bool serve_requests_locked(std::unique_lock<std::mutex> &lock) override;

private:
    bool write_surface_png_on_render_thread(const char *path);
    bool read_surface_pixels_on_render_thread(std::vector<uint32_t> &out_pixels, int &out_width, int &out_height);

    glx_context_provider *glx_provider_ = nullptr;

    // Render-thread requests; guarded by mutex_.
    std::string screenshot_path_;
    bool screenshot_pending_ = false;
    bool screenshot_finished_ = false;
    bool screenshot_ok_ = false;
    bool readback_pending_ = false;
    bool readback_finished_ = false;
    bool readback_ok_ = false;
    std::vector<uint32_t> readback_pixels_;
    int readback_width_ = 0;
    int readback_height_ = 0;
};

bool glx_renderer::create(const glx_environment_ref &environment_ref) {
    if (!owner_.mpv()) {
        LOGE("create_glx_renderer: mpv handle is null");
        return false;
    }
    glx_render_environment environment;
    environment.display = reinterpret_cast<Display *>(static_cast<uintptr_t>(environment_ref.display));
    environment.share_context = reinterpret_cast<GLXContext>(static_cast<uintptr_t>(environment_ref.share_context));
    environment.screen = environment_ref.screen;
    environment.identity = environment_ref.identity;
    std::string error;
    glx_provider_ = glx_context_provider::create(environment, &error);
    if (!glx_provider_) {
        LOGE("cannot create shared GLX producer context: %s", error.c_str());
        return false;
    }
    return start_render_thread();
}

bool glx_renderer::on_thread_start() {
    if (!glx_provider_->make_current()) return false;
    mpv_opengl_init_params gl_init_params{
        .get_proc_address = glx_get_proc_address,
        .get_proc_address_ctx = glx_provider_,
    };
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init_params},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    const int result = mpv_render_context_create(&render_context_, owner_.mpv(), params);
    if (result < 0) {
        render_context_ = nullptr;
        LOGE("mpv_render_context_create(OpenGL) failed: %s", mpv_error_string(result));
        glx_provider_->clear_current();
        return false;
    }
    LOGI("mpv GL producer: %s / %s", glGetString(GL_VERSION), glGetString(GL_RENDERER));
    attach_update_callback();
    return true;
}

void glx_renderer::on_thread_exit() {
    // Teardown must run in B's owner thread while B is current (the rings were already
    // released there by the loop).
    detach_update_callback();
    if (render_context_) {
        mpv_render_context_free(render_context_);
        render_context_ = nullptr;
    }
    glx_provider_->clear_current();
}

void glx_renderer::after_shutdown() {
    if (glx_provider_) {
        if (!glx_provider_->destroy()) LOGE("GLX producer teardown failed: %s", glx_provider_->last_error().c_str());
        delete glx_provider_;
        glx_provider_ = nullptr;
    }
}

bool glx_renderer::serve_requests_locked(std::unique_lock<std::mutex> &lock) {
    if (screenshot_pending_) {
        const std::string path = screenshot_path_;
        screenshot_pending_ = false;
        lock.unlock();
        const bool saved = write_surface_png_on_render_thread(path.c_str());
        lock.lock();
        screenshot_ok_ = saved;
        screenshot_finished_ = true;
        cv_.notify_all();
        return true;
    }
    if (readback_pending_) {
        readback_pending_ = false;
        lock.unlock();
        std::vector<uint32_t> pixels;
        int width = 0, height = 0;
        const bool read = read_surface_pixels_on_render_thread(pixels, width, height);
        lock.lock();
        readback_pixels_ = std::move(pixels);
        readback_width_ = width;
        readback_height_ = height;
        readback_ok_ = read;
        readback_finished_ = true;
        cv_.notify_all();
        return true;
    }
    return false;
}

bool glx_renderer::allocate_buffer(opengl_buffer &buffer, int width, int height) {
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

void glx_renderer::destroy_buffer(opengl_buffer &buffer) {
    if (buffer.fbo) glDeleteFramebuffers(1, &buffer.fbo);
    if (buffer.texture) glDeleteTextures(1, &buffer.texture);
}

bool glx_renderer::render_into(const opengl_buffer &buffer) {
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

bool glx_renderer::write_surface_png_on_render_thread(const char *path) {
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

bool glx_renderer::save_surface_png(const char *path) {
    if (!path) return false;
    std::unique_lock<std::mutex> lock(mutex_);
    if (!buffers_allocated_ || latest_index_ < 0 || screenshot_pending_) return false;
    screenshot_path_ = path;
    screenshot_pending_ = true;
    screenshot_finished_ = false;
    cv_.notify_all();
    cv_.wait(lock, [this] { return screenshot_finished_ || quit_; });
    return screenshot_finished_ && screenshot_ok_;
}

bool glx_renderer::read_surface_pixels_on_render_thread(
    std::vector<uint32_t> &out_pixels, int &out_width, int &out_height) {
    if (!buffers_allocated_ || latest_index_ < 0) return false;
    const opengl_buffer &buffer = buffers_[latest_index_];
    const size_t pixel_count = static_cast<size_t>(buffer_width_) * buffer_height_;
    std::vector<uint8_t> rgba(pixel_count * 4);
    glBindFramebuffer(GL_FRAMEBUFFER, buffer.fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, buffer_width_, buffer_height_, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (glGetError() != GL_NO_ERROR) return false;

    out_pixels.resize(pixel_count);
    for (int y = 0; y < buffer_height_; ++y) {
        const size_t source_row = static_cast<size_t>(buffer_height_ - 1 - y) * buffer_width_;
        const size_t target_row = static_cast<size_t>(y) * buffer_width_;
        for (int x = 0; x < buffer_width_; ++x) {
            const size_t source = (source_row + x) * 4;
            out_pixels[target_row + x] = 0xFF000000u |
                (static_cast<uint32_t>(rgba[source]) << 16) |
                (static_cast<uint32_t>(rgba[source + 1]) << 8) |
                static_cast<uint32_t>(rgba[source + 2]);
        }
    }
    out_width = buffer_width_;
    out_height = buffer_height_;
    return true;
}

bool glx_renderer::read_surface_pixels(
    std::vector<uint32_t> &out_pixels, int &out_width, int &out_height) {
    std::unique_lock<std::mutex> lock(mutex_);
    if (!buffers_allocated_ || latest_index_ < 0 || readback_pending_) return false;
    readback_pixels_.clear();
    readback_width_ = readback_height_ = 0;
    readback_pending_ = true;
    readback_finished_ = false;
    readback_ok_ = false;
    cv_.notify_all();
    cv_.wait(lock, [this] { return readback_finished_ || quit_; });
    if (!readback_finished_ || !readback_ok_) return false;
    out_pixels = readback_pixels_;
    out_width = readback_width_;
    out_height = readback_height_;
    return true;
}

} // namespace

std::shared_ptr<desktop_renderer> create_glx_renderer(mpv_handle_t &owner, const glx_environment_ref &environment) {
    auto renderer = std::make_shared<glx_renderer>(owner);
    if (!renderer->create(environment)) return nullptr;  // the destructor cleans up
    return renderer;
}

} // namespace mediampv

#endif // MEDIAMPV_LINUX_DESKTOP
