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
// Threading model: the render thread owns the CGL context (current for its lifetime)
// and is the only thread that touches GL or mutates the buffer ring. mpv's update
// callback, buffer reconfiguration (resize) and consumer acks are requests posted under
// render_mutex_; consumers read the packed frame_state_ and sample the latest buffer.
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

#include <CoreGraphics/CoreGraphics.h>
#include <ImageIO/ImageIO.h>

#include <dlfcn.h>
#include <thread>

#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_gl.h>

#include "mpv_handle_t.h"
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

bool mpv_handle_t::create_render_context() {
    if (!handle_) {
        LOG(this, LOG_LEVEL_ERROR, "create_render_context: mpv handle is null");
        return false;
    }
    if (render_context_) return true;

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
        LOG(this, LOG_LEVEL_ERROR, "CGLChoosePixelFormat failed: %d", cgl_err);
        return false;
    }
    CGLContextObj context = nullptr;
    cgl_err = CGLCreateContext(pixel_format, nullptr, &context);
    CGLDestroyPixelFormat(pixel_format);
    if (cgl_err != kCGLNoError || !context) {
        LOG(this, LOG_LEVEL_ERROR, "CGLCreateContext failed: %d", cgl_err);
        return false;
    }

    CGLSetCurrentContext(context);
    LOG(this, LOG_LEVEL_INFO, "mpv render GL: %s / %s",
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
    int create_result = mpv_render_context_create(&render_context_, handle_, params);
    CGLSetCurrentContext(nullptr);
    if (create_result < 0) {
        LOG(this, LOG_LEVEL_ERROR,
            "mpv_render_context_create failed: %s", mpv_error_string(create_result));
        render_context_ = nullptr;
        CGLDestroyContext(context);
        return false;
    }

    cgl_context_ = context;
    mpv_render_context_set_update_callback(render_context_, &mpv_handle_t::on_render_update, this);
    start_render_thread();
    return true;
}

bool mpv_handle_t::destroy_render_context() {
    cleanup_render_resources();
    return true;
}

bool mpv_handle_t::set_surface_config(int width, int height, int64_t mtl_device_ptr) {
    if (!render_thread_) return false;
    {
        std::lock_guard<std::mutex> guard(render_mutex_);
        pending_width_ = width;
        pending_height_ = height;
        pending_device_ptr_ = mtl_device_ptr;
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
    if (!buffers_allocated_ || index < 0 || index >= kMacosBufferCount) return 0;
    return (int64_t) (uintptr_t) buffers_[index].mtl_texture;
}

bool mpv_handle_t::ack_retired_buffers() {
    {
        std::lock_guard<std::mutex> guard(render_mutex_);
        retire_ack_pending_ = true;
    }
    render_cv_.notify_all();
    return true;
}

bool mpv_handle_t::has_metal_surface() {
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
    auto context = (CGLContextObj) cgl_context_;
    CGLSetCurrentContext(context);

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

        int next = (latest_index_ + 1) % kMacosBufferCount;
        macos_buffer target = buffers_[next];
        lock.unlock();
        bool rendered = render_into(target);
        lock.lock();
        if (rendered) {
            latest_index_ = next;
            ++frame_serial_;
            publish_state_locked();
            lock.unlock();
            // Notify only after the frame is actually in the IOSurface, so a consumer
            // waking on this never samples a stale buffer.
            notify_render_update();
            lock.lock();
        }
    }
    lock.unlock();
    CGLSetCurrentContext(nullptr);
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
        for (int i = 0; i < kMacosBufferCount; ++i) {
            retired_buffers_[i] = buffers_[i];
            buffers_[i] = macos_buffer{};
        }
        has_retired_buffers_ = true;
        buffers_allocated_ = false;
    }

    id<MTLDevice> device = device_ptr != 0
        ? (__bridge id<MTLDevice>) (void *) (uintptr_t) device_ptr
        : MTLCreateSystemDefaultDevice();
    bool ok = device != nil;
    for (int i = 0; i < kMacosBufferCount && ok; ++i) {
        ok = allocate_buffer(buffers_[i], width, height, (__bridge void *) device);
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
    LOG(this, LOG_LEVEL_INFO,
        "buffer ring allocated %dx%d gen=%u", width, height, buffer_generation_);
    return true;
}

bool mpv_handle_t::allocate_buffer(macos_buffer &buffer, int width, int height, void *mtl_device) {
    NSDictionary *surface_props = @{
        (id) kIOSurfaceWidth: @(width),
        (id) kIOSurfaceHeight: @(height),
        (id) kIOSurfaceBytesPerElement: @4,
        (id) kIOSurfacePixelFormat: @((uint32_t) 'BGRA'),
    };
    IOSurfaceRef surface = IOSurfaceCreate((__bridge CFDictionaryRef) surface_props);
    if (!surface) {
        LOG(this, LOG_LEVEL_ERROR, "IOSurfaceCreate failed (%dx%d)", width, height);
        return false;
    }

    auto context = (CGLContextObj) cgl_context_;
    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_RECTANGLE, texture);
    CGLError cgl_err = CGLTexImageIOSurface2D(
        context, GL_TEXTURE_RECTANGLE, GL_RGBA, width, height,
        GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, surface, 0);
    glBindTexture(GL_TEXTURE_RECTANGLE, 0);
    if (cgl_err != kCGLNoError) {
        LOG(this, LOG_LEVEL_ERROR, "CGLTexImageIOSurface2D failed: %d", cgl_err);
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
        LOG(this, LOG_LEVEL_ERROR, "IOSurface FBO incomplete: 0x%x", fbo_status);
        glDeleteFramebuffers(1, &fbo);
        glDeleteTextures(1, &texture);
        CFRelease(surface);
        return false;
    }

    id<MTLDevice> device = (__bridge id<MTLDevice>) mtl_device;
    MTLTextureDescriptor *descriptor = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                     width:(NSUInteger) width
                                    height:(NSUInteger) height
                                 mipmapped:NO];
    descriptor.usage = MTLTextureUsageShaderRead;
    descriptor.storageMode = MTLStorageModeShared;
    id<MTLTexture> metal_texture = [device newTextureWithDescriptor:descriptor
                                                          iosurface:surface
                                                              plane:0];
    if (!metal_texture) {
        LOG(this, LOG_LEVEL_ERROR, "newTextureWithDescriptor:iosurface: failed");
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

void mpv_handle_t::destroy_buffer_ring(macos_buffer *ring) {
    for (int i = 0; i < kMacosBufferCount; ++i) {
        macos_buffer &buffer = ring[i];
        if (buffer.fbo) glDeleteFramebuffers(1, &buffer.fbo);
        if (buffer.texture) glDeleteTextures(1, &buffer.texture);
        if (buffer.mtl_texture) CFRelease(buffer.mtl_texture);
        if (buffer.io_surface) CFRelease((IOSurfaceRef) buffer.io_surface);
        buffer = macos_buffer{};
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

bool mpv_handle_t::render_into(const macos_buffer &buffer) {
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

    auto context = (CGLContextObj) cgl_context_;
    if (context) {
        CGLSetCurrentContext(context);
        std::lock_guard<std::mutex> guard(render_mutex_);
        if (has_retired_buffers_) {
            destroy_buffer_ring(retired_buffers_);
            has_retired_buffers_ = false;
        }
        if (buffers_allocated_) {
            destroy_buffer_ring(buffers_);
            buffers_allocated_ = false;
        }
        latest_index_ = -1;
        buffer_width_ = buffer_height_ = 0;
        buffer_device_ptr_ = 0;
        publish_state_locked();
        CGLSetCurrentContext(nullptr);
    }

    if (render_context_) {
        mpv_render_context_set_update_callback(render_context_, nullptr, nullptr);
        if (context) CGLSetCurrentContext(context);
        mpv_render_context_free(render_context_);
        if (context) CGLSetCurrentContext(nullptr);
        render_context_ = nullptr;
    }

    if (cgl_context_) {
        CGLDestroyContext((CGLContextObj) cgl_context_);
        cgl_context_ = nullptr;
    }
}

// Copies the latest rendered frame (BGRA IOSurface) into ARGB_8888 ints. BGRA words
// read as little-endian uint32 are already 0xAARRGGBB; alpha is forced opaque because
// mpv leaves it undefined for opaque video. Holds render_mutex_ so the render thread
// cannot cycle the ring back onto this buffer mid-read.
bool mpv_handle_t::read_surface_pixels(
    std::vector<uint32_t> &out_pixels, int &out_width, int &out_height) {
    std::lock_guard<std::mutex> guard(render_mutex_);
    if (!buffers_allocated_ || latest_index_ < 0) return false;
    auto surface = (IOSurfaceRef) buffers_[latest_index_].io_surface;
    if (!surface) return false;

    if (IOSurfaceLock(surface, kIOSurfaceLockReadOnly, nullptr) != kIOReturnSuccess) {
        LOG(this, LOG_LEVEL_ERROR, "read_surface_pixels: IOSurfaceLock failed");
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

// Writes the latest rendered frame (BGRA IOSurface) as PNG. Independent of mpv's
// screenshot pipeline, which cannot convert hwdec (videotoolbox) frames without a GPU
// download. Holds render_mutex_ for the whole save so the render thread cannot cycle
// the ring back onto this buffer mid-read.
bool mpv_handle_t::save_surface_png(const char *path) {
    std::lock_guard<std::mutex> guard(render_mutex_);
    if (!buffers_allocated_ || latest_index_ < 0 || !path) {
        LOG(this, LOG_LEVEL_WARN, "save_surface_png: no surface/frame available");
        return false;
    }
    auto surface = (IOSurfaceRef) buffers_[latest_index_].io_surface;
    if (!surface) {
        LOG(this, LOG_LEVEL_ERROR, "save_surface_png: IOSurface is null");
        return false;
    }

    if (IOSurfaceLock(surface, kIOSurfaceLockReadOnly, nullptr) != kIOReturnSuccess) {
        LOG(this, LOG_LEVEL_ERROR, "IOSurfaceLock failed");
        return false;
    }
    bool ok = false;
    void *base = IOSurfaceGetBaseAddress(surface);
    size_t bpr = IOSurfaceGetBytesPerRow(surface);
    size_t width = IOSurfaceGetWidth(surface);
    size_t height = IOSurfaceGetHeight(surface);

    CGColorSpaceRef color_space = CGColorSpaceCreateDeviceRGB();
    CGDataProviderRef provider = CGDataProviderCreateWithData(nullptr, base, bpr * height, nullptr);
    CGImageRef image = CGImageCreate(
        width, height, 8, 32, bpr, color_space,
        kCGBitmapByteOrder32Little | kCGImageAlphaNoneSkipFirst, // BGRA in memory, alpha ignored
        provider, nullptr, false, kCGRenderingIntentDefault);
    if (image) {
        NSURL *url = [NSURL fileURLWithPath:[NSString stringWithUTF8String:path]];
        CGImageDestinationRef dest = CGImageDestinationCreateWithURL(
            (__bridge CFURLRef) url, CFSTR("public.png"), 1, nullptr);
        if (dest) {
            CGImageDestinationAddImage(dest, image, nullptr);
            ok = CGImageDestinationFinalize(dest);
            CFRelease(dest);
        }
        CGImageRelease(image);
    }
    CGDataProviderRelease(provider);
    CGColorSpaceRelease(color_space);
    IOSurfaceUnlock(surface, kIOSurfaceLockReadOnly, nullptr);
    if (!ok) LOG(this, LOG_LEVEL_ERROR, "save_surface_png failed for %s", path);
    return ok;
}

} // namespace mediampv

#endif // __APPLE__
