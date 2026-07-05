/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

// macOS render path: mpv renders (hwdec=videotoolbox stays on GPU) through the libmpv
// OpenGL render API on an offscreen CGL context, into an FBO whose color attachment is
// an IOSurface-backed GL_TEXTURE_RECTANGLE. The same IOSurface is wrapped as an
// MTLTexture on the caller-provided MTLDevice (Skia's device), so Compose/Skia can
// sample the video frames zero-copy.

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
    FP;
    if (!handle_) return false;
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
        LOG("CGLChoosePixelFormat failed: %d\n", cgl_err);
        return false;
    }
    CGLContextObj context = nullptr;
    cgl_err = CGLCreateContext(pixel_format, nullptr, &context);
    CGLDestroyPixelFormat(pixel_format);
    if (cgl_err != kCGLNoError || !context) {
        LOG("CGLCreateContext failed: %d\n", cgl_err);
        return false;
    }

    CGLSetCurrentContext(context);
    LOG("mpv render GL: %s / %s\n",
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
        LOG("mpv_render_context_create failed: %s\n", mpv_error_string(create_result));
        render_context_ = nullptr;
        CGLDestroyContext(context);
        return false;
    }

    cgl_context_ = context;
    mpv_render_context_set_update_callback(render_context_, &mpv_handle_t::on_render_update, this);
    return true;
}

bool mpv_handle_t::destroy_render_context() {
    FP;
    cleanup_render_resources();
    return true;
}

int64_t mpv_handle_t::create_metal_surface(int width, int height, int64_t mtl_device_ptr) {
    FP;
    LOCK(texture_lock);
    if (!cgl_context_ || width <= 0 || height <= 0) return 0;

    auto context = (CGLContextObj) cgl_context_;
    CGLSetCurrentContext(context);

    // Release the previous chain first (GL objects need the context current).
    if (fbo_) { glDeleteFramebuffers(1, &fbo_); fbo_ = 0; }
    if (texture_) { glDeleteTextures(1, &texture_); texture_ = 0; }
    if (mtl_texture_) { CFRelease(mtl_texture_); mtl_texture_ = nullptr; }
    if (io_surface_) { CFRelease((IOSurfaceRef) io_surface_); io_surface_ = nullptr; }
    width_ = height_ = 0;

    NSDictionary *surface_props = @{
        (id) kIOSurfaceWidth: @(width),
        (id) kIOSurfaceHeight: @(height),
        (id) kIOSurfaceBytesPerElement: @4,
        (id) kIOSurfacePixelFormat: @((uint32_t) 'BGRA'),
    };
    IOSurfaceRef surface = IOSurfaceCreate((__bridge CFDictionaryRef) surface_props);
    if (!surface) {
        LOG("IOSurfaceCreate failed (%dx%d)\n", width, height);
        CGLSetCurrentContext(nullptr);
        return 0;
    }

    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_RECTANGLE, texture);
    CGLError cgl_err = CGLTexImageIOSurface2D(
        context, GL_TEXTURE_RECTANGLE, GL_RGBA, width, height,
        GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, surface, 0);
    glBindTexture(GL_TEXTURE_RECTANGLE, 0);
    if (cgl_err != kCGLNoError) {
        LOG("CGLTexImageIOSurface2D failed: %d\n", cgl_err);
        glDeleteTextures(1, &texture);
        CFRelease(surface);
        CGLSetCurrentContext(nullptr);
        return 0;
    }

    GLuint fbo = 0;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_RECTANGLE, texture, 0);
    GLenum fbo_status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    CGLSetCurrentContext(nullptr);
    if (fbo_status != GL_FRAMEBUFFER_COMPLETE) {
        LOG("IOSurface FBO incomplete: 0x%x\n", fbo_status);
        CGLSetCurrentContext(context);
        glDeleteFramebuffers(1, &fbo);
        glDeleteTextures(1, &texture);
        CGLSetCurrentContext(nullptr);
        CFRelease(surface);
        return 0;
    }

    id<MTLDevice> device = mtl_device_ptr != 0
        ? (__bridge id<MTLDevice>) (void *) (uintptr_t) mtl_device_ptr
        : MTLCreateSystemDefaultDevice();
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
        LOG("newTextureWithDescriptor:iosurface: failed\n");
        CGLSetCurrentContext(context);
        glDeleteFramebuffers(1, &fbo);
        glDeleteTextures(1, &texture);
        CGLSetCurrentContext(nullptr);
        CFRelease(surface);
        return 0;
    }

    io_surface_ = (void *) surface;
    texture_ = texture;
    fbo_ = fbo;
    mtl_texture_ = (void *) CFBridgingRetain(metal_texture);
    width_ = width;
    height_ = height;
    LOG("metal surface created %dx%d (IOSurfaceID=%u)\n", width, height, IOSurfaceGetID(surface));
    return (int64_t) (uintptr_t) mtl_texture_;
}

bool mpv_handle_t::release_metal_surface() {
    FP;
    LOCK(texture_lock);
    if (!cgl_context_) return false;

    auto context = (CGLContextObj) cgl_context_;
    CGLSetCurrentContext(context);
    if (fbo_) { glDeleteFramebuffers(1, &fbo_); fbo_ = 0; }
    if (texture_) { glDeleteTextures(1, &texture_); texture_ = 0; }
    CGLSetCurrentContext(nullptr);
    if (mtl_texture_) { CFRelease(mtl_texture_); mtl_texture_ = nullptr; }
    if (io_surface_) { CFRelease((IOSurfaceRef) io_surface_); io_surface_ = nullptr; }
    width_ = height_ = 0;
    return true;
}

bool mpv_handle_t::render_frame() {
    LOCK(texture_lock);
    if (!render_context_ || !cgl_context_ || !fbo_) return false;

    auto context = (CGLContextObj) cgl_context_;
    CGLSetCurrentContext(context);

    mpv_opengl_fbo fbo{(int) fbo_, width_, height_, 0};
    int flip_y = 1;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
        {MPV_RENDER_PARAM_FLIP_Y, &flip_y},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    int render_result = mpv_render_context_render(render_context_, params);

    // mpv leaves the alpha channel undefined for opaque video; force it to 1 so
    // Skia's premultiplied sampling does not discard the frame.
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_TRUE);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    glFlush();
    CGLSetCurrentContext(nullptr);
    return render_result >= 0;
}

void mpv_handle_t::cleanup_render_resources() {
    release_metal_surface();

    if (render_context_) {
        mpv_render_context_set_update_callback(render_context_, nullptr, nullptr);
        auto context = (CGLContextObj) cgl_context_;
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

} // namespace mediampv

#endif // __APPLE__
