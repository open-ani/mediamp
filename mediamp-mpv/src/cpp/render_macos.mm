/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

// macOS render path: a dedicated render thread drives mpv (hwdec=videotoolbox stays on
// GPU) through the libmpv OpenGL render API on an offscreen CGL context, into a ring of
// FBOs whose color attachments are IOSurface-backed GL_TEXTURE_RECTANGLEs. Each
// IOSurface is also wrapped as an MTLTexture on the consumer-provided MTLDevice (Skia's
// device), so Compose/Skia can sample the video frames zero-copy.
//
// Threading model: the shared ring protocol (surface_ring.h). The render thread keeps
// the CGL context current for its lifetime and is the only thread that touches GL.
// Because rendering never happens on the UI thread, buffer reallocation and glFinish
// cost the video pipeline nothing user-visible: during a resize the consumer keeps
// drawing the previous generation (kept alive until acked), and swaps to the new ring
// the first time it observes a frame in it.

#ifdef __APPLE__

#define GL_SILENCE_DEPRECATION 1

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

#include <IOSurface/IOSurface.h>
#include <OpenGL/OpenGL.h>
#include <OpenGL/gl3.h>
#include <OpenGL/CGLIOSurface.h>

#include <dlfcn.h>

#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_gl.h>

#include "surface_ring.h"
#include "log.h"

#ifndef GL_BGRA
#define GL_BGRA 0x80E1
#endif
#ifndef GL_UNSIGNED_INT_8_8_8_8_REV
#define GL_UNSIGNED_INT_8_8_8_8_REV 0x8367
#endif

namespace {

void *macos_get_proc_address(void *, const char *name) {
    static void *gl_framework = dlopen(
        "/System/Library/Frameworks/OpenGL.framework/OpenGL", RTLD_LAZY | RTLD_GLOBAL);
    return gl_framework ? dlsym(gl_framework, name) : nullptr;
}

} // namespace

namespace mediampv {

namespace {

struct macos_buffer {
    void *io_surface = nullptr;   // IOSurfaceRef
    void *mtl_texture = nullptr;  // retained id<MTLTexture>
    uint32_t texture = 0;         // GL_TEXTURE_RECTANGLE bound to the IOSurface
    uint32_t fbo = 0;
};

class macos_renderer final : public surface_ring<macos_buffer> {
public:
    explicit macos_renderer(mpv_handle_t &owner) : surface_ring(owner, "Metal") {}
    ~macos_renderer() override { shutdown(); }

    bool create();

    bool read_surface_pixels(std::vector<uint32_t> &pixels, int &width, int &height) override;

protected:
    // The render thread keeps the CGL context current for its whole lifetime.
    bool on_thread_start() override {
        CGLSetCurrentContext(cgl_context_);
        return true;
    }
    void on_thread_exit() override { CGLSetCurrentContext(nullptr); }
    void after_shutdown() override;
    // MTLTextures are created on the consumer-provided MTLDevice (0 = system default).
    bool prepare_device_locked(int64_t consumer_device, bool changed) override;
    void release_device_locked() override { mtl_device_ = nil; }
    bool allocate_buffer(macos_buffer &buffer, int width, int height) override;
    void destroy_buffer(macos_buffer &buffer) override;
    int64_t texture_handle(const macos_buffer &buffer) const override {
        return (int64_t) (uintptr_t) buffer.mtl_texture;
    }
    bool render_into(const macos_buffer &buffer) override;
    bool render_frame_pixels_on_render_thread(int width, int height, std::vector<uint32_t> &pixels) override;

private:
    CGLContextObj cgl_context_ = nullptr;
    id<MTLDevice> mtl_device_ = nil;  // strong (ARC)
};

bool macos_renderer::create() {
    mpv_handle *mpv = owner_.mpv();
    if (!mpv) {
        LOG(&owner_, LOG_LEVEL_ERROR, "create_macos_renderer: mpv handle is null");
        return false;
    }

    CGLPixelFormatAttribute attrs[] = {
        kCGLPFAOpenGLProfile, (CGLPixelFormatAttribute) kCGLOGLPVersion_3_2_Core,
        kCGLPFAAccelerated,
        kCGLPFAAllowOfflineRenderers,
        (CGLPixelFormatAttribute) 0,
    };
    CGLPixelFormatObj pixel_format = nullptr;
    GLint num_pixel_formats = 0;
    CGLError cgl_err = CGLChoosePixelFormat(attrs, &pixel_format, &num_pixel_formats);
    if (cgl_err != kCGLNoError || !pixel_format) {
        LOG(&owner_, LOG_LEVEL_ERROR, "CGLChoosePixelFormat failed: %d", cgl_err);
        return false;
    }
    CGLContextObj context = nullptr;
    cgl_err = CGLCreateContext(pixel_format, nullptr, &context);
    CGLDestroyPixelFormat(pixel_format);
    if (cgl_err != kCGLNoError || !context) {
        LOG(&owner_, LOG_LEVEL_ERROR, "CGLCreateContext failed: %d", cgl_err);
        return false;
    }
    cgl_context_ = context;

    CGLSetCurrentContext(context);
    LOG(&owner_, LOG_LEVEL_INFO, "mpv render GL: %s / %s",
        (const char *) glGetString(GL_VERSION), (const char *) glGetString(GL_RENDERER));

    mpv_opengl_init_params gl_init_params{
        .get_proc_address = macos_get_proc_address,
        .get_proc_address_ctx = nullptr,
    };
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init_params},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    const int create_result = mpv_render_context_create(&render_context_, mpv, params);
    CGLSetCurrentContext(nullptr);
    if (create_result < 0) {
        LOG(&owner_, LOG_LEVEL_ERROR,
            "mpv_render_context_create failed: %s", mpv_error_string(create_result));
        render_context_ = nullptr;
        return false;
    }
    attach_update_callback();
    return start_render_thread();
}

void macos_renderer::after_shutdown() {
    detach_update_callback();
    if (render_context_) {
        if (cgl_context_) CGLSetCurrentContext(cgl_context_);
        mpv_render_context_free(render_context_);
        if (cgl_context_) CGLSetCurrentContext(nullptr);
        render_context_ = nullptr;
    }
    if (cgl_context_) {
        CGLDestroyContext(cgl_context_);
        cgl_context_ = nullptr;
    }
    mtl_device_ = nil;
}

bool macos_renderer::prepare_device_locked(int64_t consumer_device, bool) {
    mtl_device_ = consumer_device != 0
        ? (__bridge id<MTLDevice>) (void *) (uintptr_t) consumer_device
        : MTLCreateSystemDefaultDevice();
    return mtl_device_ != nil;
}

bool macos_renderer::allocate_buffer(macos_buffer &buffer, int width, int height) {
    NSDictionary *surface_props = @{
        (id) kIOSurfaceWidth: @(width),
        (id) kIOSurfaceHeight: @(height),
        (id) kIOSurfaceBytesPerElement: @4,
        (id) kIOSurfacePixelFormat: @((uint32_t) 'BGRA'),
    };
    IOSurfaceRef surface = IOSurfaceCreate((__bridge CFDictionaryRef) surface_props);
    if (!surface) {
        LOG(&owner_, LOG_LEVEL_ERROR, "IOSurfaceCreate failed (%dx%d)", width, height);
        return false;
    }

    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_RECTANGLE, texture);
    CGLError cgl_err = CGLTexImageIOSurface2D(
        cgl_context_, GL_TEXTURE_RECTANGLE, GL_RGBA, width, height,
        GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, surface, 0);
    glBindTexture(GL_TEXTURE_RECTANGLE, 0);
    if (cgl_err != kCGLNoError) {
        LOG(&owner_, LOG_LEVEL_ERROR, "CGLTexImageIOSurface2D failed: %d", cgl_err);
        glDeleteTextures(1, &texture);
        CFRelease(surface);
        return false;
    }

    GLuint fbo = 0;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_RECTANGLE, texture, 0);
    GLenum fbo_status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (fbo_status != GL_FRAMEBUFFER_COMPLETE) {
        LOG(&owner_, LOG_LEVEL_ERROR, "IOSurface FBO incomplete: 0x%x", fbo_status);
        glDeleteFramebuffers(1, &fbo);
        glDeleteTextures(1, &texture);
        CFRelease(surface);
        return false;
    }

    MTLTextureDescriptor *descriptor = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                     width:(NSUInteger) width
                                    height:(NSUInteger) height
                                 mipmapped:NO];
    descriptor.usage = MTLTextureUsageShaderRead;
    descriptor.storageMode = MTLStorageModeShared;
    id<MTLTexture> metal_texture = [mtl_device_ newTextureWithDescriptor:descriptor
                                                               iosurface:surface
                                                                   plane:0];
    if (!metal_texture) {
        LOG(&owner_, LOG_LEVEL_ERROR, "newTextureWithDescriptor:iosurface: failed");
        glDeleteFramebuffers(1, &fbo);
        glDeleteTextures(1, &texture);
        CFRelease(surface);
        return false;
    }

    buffer.io_surface = (void *) surface;
    buffer.texture = texture;
    buffer.fbo = fbo;
    buffer.mtl_texture = (void *) CFBridgingRetain(metal_texture);
    return true;
}

void macos_renderer::destroy_buffer(macos_buffer &buffer) {
    if (buffer.fbo) glDeleteFramebuffers(1, &buffer.fbo);
    if (buffer.texture) glDeleteTextures(1, &buffer.texture);
    if (buffer.mtl_texture) CFRelease(buffer.mtl_texture);
    if (buffer.io_surface) CFRelease((IOSurfaceRef) buffer.io_surface);
}

bool macos_renderer::render_into(const macos_buffer &buffer) {
    if (!render_context_ || !buffer.fbo) return false;

    mpv_opengl_fbo fbo{(int) buffer.fbo, buffer_width_, buffer_height_, 0};
    // No FLIP_Y: mpv then writes the image top-down in surface memory (row 0 = top),
    // which is what both Skia's SurfaceOrigin.TOP_LEFT sampling and the PNG readback
    // expect. Verified against ffmpeg-extracted reference frames.
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    int render_result = mpv_render_context_render(render_context_, params);

    // mpv leaves the alpha channel undefined for opaque video; force it to 1 so
    // Skia's premultiplied sampling does not discard the frame.
    glBindFramebuffer(GL_FRAMEBUFFER, buffer.fbo);
    glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_TRUE);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    // glFinish, not glFlush: Metal samples this IOSurface right after the buffer is
    // published; a mere flush races with GL completion under load (black frames),
    // finish guarantees visibility. Runs on the render thread, so it never blocks UI.
    glFinish();
    return render_result >= 0;
}

// Copies the latest rendered frame (BGRA IOSurface) into ARGB_8888 ints. BGRA words
// read as little-endian uint32 are already 0xAARRGGBB; alpha is forced opaque because
// mpv leaves it undefined for opaque video. Holds mutex_ so the render thread cannot
// cycle the ring back onto this buffer mid-read.
bool macos_renderer::read_surface_pixels(
    std::vector<uint32_t> &out_pixels, int &out_width, int &out_height) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (!buffers_allocated_ || latest_index_ < 0) return false;
    auto surface = (IOSurfaceRef) buffers_[latest_index_].io_surface;
    if (!surface) return false;

    if (IOSurfaceLock(surface, kIOSurfaceLockReadOnly, nullptr) != kIOReturnSuccess) {
        LOG(&owner_, LOG_LEVEL_ERROR, "read_surface_pixels: IOSurfaceLock failed");
        return false;
    }
    const auto *base = (const uint8_t *) IOSurfaceGetBaseAddress(surface);
    size_t bpr = IOSurfaceGetBytesPerRow(surface);
    size_t width = IOSurfaceGetWidth(surface);
    size_t height = IOSurfaceGetHeight(surface);
    out_pixels.resize(width * height);
    for (size_t y = 0; y < height; ++y) {
        const auto *src = (const uint32_t *) (base + y * bpr);
        uint32_t *dst = out_pixels.data() + y * width;
        for (size_t x = 0; x < width; ++x) dst[x] = src[x] | 0xFF000000u;
    }
    IOSurfaceUnlock(surface, kIOSurfaceLockReadOnly, nullptr);
    out_width = (int) width;
    out_height = (int) height;
    return true;
}

// A private FBO of the requested size on the render thread's CGL context: mpv redraws
// the current frame into it (the ring and its consumer are untouched). Same orientation
// contract as the ring (no FLIP_Y: mpv writes row 0 = top), and glReadPixels returns rows
// from row 0, so the readback is top-down as is.
bool macos_renderer::render_frame_pixels_on_render_thread(int width, int height, std::vector<uint32_t> &pixels) {
    if (!render_context_) return false;
    GLuint texture = 0, fbo = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glBindTexture(GL_TEXTURE_2D, 0);
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    const GLenum fbo_status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    bool ok = fbo_status == GL_FRAMEBUFFER_COMPLETE;
    if (!ok) LOG(&owner_, LOG_LEVEL_ERROR, "frame request FBO incomplete: 0x%x", fbo_status);
    if (ok) {
        mpv_opengl_fbo target{(int) fbo, width, height, 0};
        mpv_render_param params[] = {
            {MPV_RENDER_PARAM_OPENGL_FBO, &target},
            {MPV_RENDER_PARAM_INVALID, nullptr},
        };
        ok = mpv_render_context_render(render_context_, params) >= 0;
        glFinish();
    }
    if (ok) {
        const size_t pixel_count = (size_t) width * height;
        std::vector<uint8_t> rgba(pixel_count * 4);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        ok = glGetError() == GL_NO_ERROR;
        if (ok) {
            pixels.resize(pixel_count);
            for (size_t i = 0; i < pixel_count; ++i) {
                pixels[i] = 0xFF000000u | ((uint32_t) rgba[i * 4] << 16) |
                    ((uint32_t) rgba[i * 4 + 1] << 8) | rgba[i * 4 + 2];
            }
        }
    }
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &texture);
    return ok;
}

} // namespace

std::shared_ptr<desktop_renderer> create_macos_renderer(mpv_handle_t &owner) {
    auto renderer = std::make_shared<macos_renderer>(owner);
    if (!renderer->create()) return nullptr;  // the destructor cleans up
    return renderer;
}

} // namespace mediampv

#endif // __APPLE__
