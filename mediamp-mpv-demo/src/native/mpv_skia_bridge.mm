/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

// Prototype bridge: libmpv (OpenGL render API, hwdec=videotoolbox) renders into an
// IOSurface-backed FBO; the same IOSurface is exposed as an MTLTexture so Skia
// (Compose Desktop's Metal backend) can sample it zero-copy.

#define GL_SILENCE_DEPRECATION 1

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

#include <IOSurface/IOSurface.h>
#include <OpenGL/OpenGL.h>
#include <OpenGL/gl3.h>
#include <OpenGL/CGLIOSurface.h>

#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_gl.h>

#include <jni.h>
#include <dlfcn.h>
#include <clocale>
#include <cstdio>
#include <cstring>
#include <atomic>
#include <thread>
#include <vector>
#include <string>

#ifndef GL_BGRA
#define GL_BGRA 0x80E1
#endif
#ifndef GL_UNSIGNED_INT_8_8_8_8_REV
#define GL_UNSIGNED_INT_8_8_8_8_REV 0x8367
#endif

#define LOGF(...) do { fprintf(stderr, "[mpv-bridge] " __VA_ARGS__); fprintf(stderr, "\n"); fflush(stderr); } while (0)

static JavaVM* g_vm = nullptr;

struct PlayerCtx {
    mpv_handle* mpv = nullptr;
    mpv_render_context* rctx = nullptr;
    CGLContextObj gl = nullptr;

    IOSurfaceRef surface = nullptr;
    GLuint tex = 0;
    GLuint fbo = 0;
    void* mtlTex = nullptr; // retained id<MTLTexture>
    int w = 0, h = 0;

    jobject listener = nullptr;
    jmethodID onRenderUpdate = nullptr;

    std::thread eventThread;
    std::atomic<bool> quit{false};
};

static void* mpv_get_proc_address(void*, const char* name) {
    static void* handle = dlopen(
        "/System/Library/Frameworks/OpenGL.framework/OpenGL", RTLD_LAZY | RTLD_GLOBAL);
    return handle ? dlsym(handle, name) : nullptr;
}

static void on_mpv_render_update(void* d) {
    auto* ctx = static_cast<PlayerCtx*>(d);
    if (!ctx->listener || !g_vm) return;
    JNIEnv* env = nullptr;
    if (g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
    env->CallVoidMethod(ctx->listener, ctx->onRenderUpdate);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

static void event_loop(PlayerCtx* ctx) {
    while (!ctx->quit.load()) {
        mpv_event* ev = mpv_wait_event(ctx->mpv, 1.0);
        switch (ev->event_id) {
            case MPV_EVENT_NONE:
                break;
            case MPV_EVENT_SHUTDOWN:
                return;
            case MPV_EVENT_LOG_MESSAGE: {
                auto* msg = static_cast<mpv_event_log_message*>(ev->data);
                fprintf(stderr, "[mpv/%s] %s: %s", msg->prefix, msg->level, msg->text);
                break;
            }
            case MPV_EVENT_PROPERTY_CHANGE: {
                auto* prop = static_cast<mpv_event_property*>(ev->data);
                if (prop->format == MPV_FORMAT_STRING && prop->data) {
                    LOGF("property %s = %s", prop->name, *static_cast<char**>(prop->data));
                }
                break;
            }
            default:
                LOGF("event: %s", mpv_event_name(ev->event_id));
                break;
        }
    }
}

static void release_surface_locked(PlayerCtx* ctx) {
    // GL context must be current when deleting GL objects.
    if (ctx->fbo) { glDeleteFramebuffers(1, &ctx->fbo); ctx->fbo = 0; }
    if (ctx->tex) { glDeleteTextures(1, &ctx->tex); ctx->tex = 0; }
    if (ctx->mtlTex) { CFRelease(ctx->mtlTex); ctx->mtlTex = nullptr; }
    if (ctx->surface) { CFRelease(ctx->surface); ctx->surface = nullptr; }
    ctx->w = ctx->h = 0;
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_8;
}

JNIEXPORT jlong JNICALL
Java_org_openani_mediamp_mpvdemo_MpvNative_create(JNIEnv* env, jclass, jstring jhwdec) {
    setlocale(LC_NUMERIC, "C");
    auto* ctx = new PlayerCtx();

    ctx->mpv = mpv_create();
    if (!ctx->mpv) { LOGF("mpv_create failed"); delete ctx; return 0; }

    mpv_set_option_string(ctx->mpv, "vo", "libmpv");
    const char* hwdec = jhwdec ? env->GetStringUTFChars(jhwdec, nullptr) : nullptr;
    mpv_set_option_string(ctx->mpv, "hwdec", hwdec ? hwdec : "videotoolbox");
    LOGF("hwdec option: %s", hwdec ? hwdec : "videotoolbox");
    if (hwdec) env->ReleaseStringUTFChars(jhwdec, hwdec);
    mpv_set_option_string(ctx->mpv, "keep-open", "yes");
    mpv_request_log_messages(ctx->mpv, "info");

    int rc = mpv_initialize(ctx->mpv);
    if (rc < 0) { LOGF("mpv_initialize failed: %s", mpv_error_string(rc)); delete ctx; return 0; }

    mpv_observe_property(ctx->mpv, 0, "hwdec-current", MPV_FORMAT_STRING);
    mpv_observe_property(ctx->mpv, 0, "video-format", MPV_FORMAT_STRING);

    CGLPixelFormatAttribute attrs[] = {
        kCGLPFAOpenGLProfile, (CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core,
        kCGLPFAAccelerated,
        kCGLPFAAllowOfflineRenderers,
        (CGLPixelFormatAttribute)0,
    };
    CGLPixelFormatObj pix = nullptr;
    GLint npix = 0;
    CGLError cglErr = CGLChoosePixelFormat(attrs, &pix, &npix);
    if (cglErr != kCGLNoError || !pix) {
        LOGF("CGLChoosePixelFormat failed: %d", cglErr);
        mpv_terminate_destroy(ctx->mpv);
        delete ctx;
        return 0;
    }
    cglErr = CGLCreateContext(pix, nullptr, &ctx->gl);
    CGLDestroyPixelFormat(pix);
    if (cglErr != kCGLNoError || !ctx->gl) {
        LOGF("CGLCreateContext failed: %d", cglErr);
        mpv_terminate_destroy(ctx->mpv);
        delete ctx;
        return 0;
    }

    CGLSetCurrentContext(ctx->gl);
    LOGF("GL_VERSION: %s", (const char*)glGetString(GL_VERSION));
    LOGF("GL_RENDERER: %s", (const char*)glGetString(GL_RENDERER));

    mpv_opengl_init_params glInit = { mpv_get_proc_address, nullptr };
    mpv_render_param params[] = {
        { MPV_RENDER_PARAM_API_TYPE, const_cast<char*>(MPV_RENDER_API_TYPE_OPENGL) },
        { MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &glInit },
        { MPV_RENDER_PARAM_INVALID, nullptr },
    };
    rc = mpv_render_context_create(&ctx->rctx, ctx->mpv, params);
    CGLSetCurrentContext(nullptr);
    if (rc < 0) {
        LOGF("mpv_render_context_create failed: %s", mpv_error_string(rc));
        CGLDestroyContext(ctx->gl);
        mpv_terminate_destroy(ctx->mpv);
        delete ctx;
        return 0;
    }
    LOGF("mpv render context created (hwdec=videotoolbox requested)");

    ctx->eventThread = std::thread(event_loop, ctx);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_org_openani_mediamp_mpvdemo_MpvNative_setUpdateListener(JNIEnv* env, jclass, jlong ptr, jobject listener) {
    auto* ctx = reinterpret_cast<PlayerCtx*>(ptr);
    if (!ctx) return;
    if (ctx->listener) {
        mpv_render_context_set_update_callback(ctx->rctx, nullptr, nullptr);
        env->DeleteGlobalRef(ctx->listener);
        ctx->listener = nullptr;
        ctx->onRenderUpdate = nullptr;
    }
    if (listener) {
        ctx->listener = env->NewGlobalRef(listener);
        jclass cls = env->GetObjectClass(listener);
        ctx->onRenderUpdate = env->GetMethodID(cls, "onRenderUpdate", "()V");
        mpv_render_context_set_update_callback(ctx->rctx, on_mpv_render_update, ctx);
    }
}

// Creates a w*h BGRA IOSurface, binds it to a GL_TEXTURE_RECTANGLE + FBO for mpv,
// and wraps it as an MTLTexture on the given MTLDevice (Skia's device).
// Returns the retained MTLTexture pointer, or 0 on failure.
JNIEXPORT jlong JNICALL
Java_org_openani_mediamp_mpvdemo_MpvNative_createSurface(JNIEnv*, jclass, jlong ptr, jint w, jint h, jlong mtlDevicePtr) {
    auto* ctx = reinterpret_cast<PlayerCtx*>(ptr);
    if (!ctx || w <= 0 || h <= 0) return 0;

    CGLSetCurrentContext(ctx->gl);
    release_surface_locked(ctx);

    NSDictionary* props = @{
        (id)kIOSurfaceWidth: @(w),
        (id)kIOSurfaceHeight: @(h),
        (id)kIOSurfaceBytesPerElement: @4,
        (id)kIOSurfacePixelFormat: @((uint32_t)'BGRA'),
    };
    ctx->surface = IOSurfaceCreate((__bridge CFDictionaryRef)props);
    if (!ctx->surface) {
        LOGF("IOSurfaceCreate failed (%dx%d)", w, h);
        CGLSetCurrentContext(nullptr);
        return 0;
    }

    glGenTextures(1, &ctx->tex);
    glBindTexture(GL_TEXTURE_RECTANGLE, ctx->tex);
    CGLError err = CGLTexImageIOSurface2D(
        ctx->gl, GL_TEXTURE_RECTANGLE, GL_RGBA, w, h,
        GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, ctx->surface, 0);
    glBindTexture(GL_TEXTURE_RECTANGLE, 0);
    if (err != kCGLNoError) {
        LOGF("CGLTexImageIOSurface2D failed: %d", err);
        release_surface_locked(ctx);
        CGLSetCurrentContext(nullptr);
        return 0;
    }

    glGenFramebuffers(1, &ctx->fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, ctx->fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_RECTANGLE, ctx->tex, 0);
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGF("FBO incomplete: 0x%x", status);
        release_surface_locked(ctx);
        CGLSetCurrentContext(nullptr);
        return 0;
    }
    CGLSetCurrentContext(nullptr);

    id<MTLDevice> device = mtlDevicePtr != 0
        ? (__bridge id<MTLDevice>)reinterpret_cast<void*>(mtlDevicePtr)
        : MTLCreateSystemDefaultDevice();
    MTLTextureDescriptor* desc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                     width:(NSUInteger)w
                                    height:(NSUInteger)h
                                 mipmapped:NO];
    desc.usage = MTLTextureUsageShaderRead;
    desc.storageMode = MTLStorageModeShared;
    id<MTLTexture> tex = [device newTextureWithDescriptor:desc iosurface:ctx->surface plane:0];
    if (!tex) {
        LOGF("newTextureWithDescriptor:iosurface: failed");
        CGLSetCurrentContext(ctx->gl);
        release_surface_locked(ctx);
        CGLSetCurrentContext(nullptr);
        return 0;
    }
    ctx->mtlTex = const_cast<void*>(CFBridgingRetain(tex));
    ctx->w = w;
    ctx->h = h;
    LOGF("surface created %dx%d (IOSurfaceID=%u, device=%s)", w, h,
         IOSurfaceGetID(ctx->surface), device.name.UTF8String);
    return reinterpret_cast<jlong>(ctx->mtlTex);
}

JNIEXPORT void JNICALL
Java_org_openani_mediamp_mpvdemo_MpvNative_renderFrame(JNIEnv*, jclass, jlong ptr) {
    auto* ctx = reinterpret_cast<PlayerCtx*>(ptr);
    if (!ctx || !ctx->fbo) return;

    CGLSetCurrentContext(ctx->gl);
    mpv_opengl_fbo fbo = { (int)ctx->fbo, ctx->w, ctx->h, 0 };
    int flipY = 1;
    mpv_render_param params[] = {
        { MPV_RENDER_PARAM_OPENGL_FBO, &fbo },
        { MPV_RENDER_PARAM_FLIP_Y, &flipY },
        { MPV_RENDER_PARAM_INVALID, nullptr },
    };
    int rc = mpv_render_context_render(ctx->rctx, params);
    if (rc < 0) LOGF("mpv_render_context_render failed: %s", mpv_error_string(rc));

    // mpv leaves the alpha channel undefined for opaque video; force it to 1
    // so Skia's premul sampling doesn't discard the frame.
    glBindFramebuffer(GL_FRAMEBUFFER, ctx->fbo);
    glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_TRUE);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    glFlush();
    CGLSetCurrentContext(nullptr);
}

JNIEXPORT jint JNICALL
Java_org_openani_mediamp_mpvdemo_MpvNative_command(JNIEnv* env, jclass, jlong ptr, jobjectArray jargs) {
    auto* ctx = reinterpret_cast<PlayerCtx*>(ptr);
    if (!ctx) return -1;
    jsize n = env->GetArrayLength(jargs);
    std::vector<std::string> storage;
    storage.reserve(n);
    std::vector<const char*> argv;
    argv.reserve(n + 1);
    for (jsize i = 0; i < n; i++) {
        auto jstr = (jstring)env->GetObjectArrayElement(jargs, i);
        const char* chars = env->GetStringUTFChars(jstr, nullptr);
        storage.emplace_back(chars);
        env->ReleaseStringUTFChars(jstr, chars);
        env->DeleteLocalRef(jstr);
    }
    for (auto& s : storage) argv.push_back(s.c_str());
    argv.push_back(nullptr);
    return mpv_command(ctx->mpv, argv.data());
}

JNIEXPORT jstring JNICALL
Java_org_openani_mediamp_mpvdemo_MpvNative_getPropertyString(JNIEnv* env, jclass, jlong ptr, jstring jname) {
    auto* ctx = reinterpret_cast<PlayerCtx*>(ptr);
    if (!ctx) return nullptr;
    const char* name = env->GetStringUTFChars(jname, nullptr);
    char* value = mpv_get_property_string(ctx->mpv, name);
    env->ReleaseStringUTFChars(jname, name);
    if (!value) return nullptr;
    jstring result = env->NewStringUTF(value);
    mpv_free(value);
    return result;
}

JNIEXPORT jdouble JNICALL
Java_org_openani_mediamp_mpvdemo_MpvNative_getPropertyDouble(JNIEnv* env, jclass, jlong ptr, jstring jname) {
    auto* ctx = reinterpret_cast<PlayerCtx*>(ptr);
    if (!ctx) return NAN;
    const char* name = env->GetStringUTFChars(jname, nullptr);
    double value = NAN;
    mpv_get_property(ctx->mpv, name, MPV_FORMAT_DOUBLE, &value);
    env->ReleaseStringUTFChars(jname, name);
    return value;
}

JNIEXPORT jint JNICALL
Java_org_openani_mediamp_mpvdemo_MpvNative_setPropertyString(JNIEnv* env, jclass, jlong ptr, jstring jname, jstring jvalue) {
    auto* ctx = reinterpret_cast<PlayerCtx*>(ptr);
    if (!ctx) return -1;
    const char* name = env->GetStringUTFChars(jname, nullptr);
    const char* value = env->GetStringUTFChars(jvalue, nullptr);
    int rc = mpv_set_property_string(ctx->mpv, name, value);
    env->ReleaseStringUTFChars(jname, name);
    env->ReleaseStringUTFChars(jvalue, value);
    return rc;
}

JNIEXPORT void JNICALL
Java_org_openani_mediamp_mpvdemo_MpvNative_destroy(JNIEnv* env, jclass, jlong ptr) {
    auto* ctx = reinterpret_cast<PlayerCtx*>(ptr);
    if (!ctx) return;

    mpv_render_context_set_update_callback(ctx->rctx, nullptr, nullptr);
    if (ctx->listener) {
        env->DeleteGlobalRef(ctx->listener);
        ctx->listener = nullptr;
    }

    ctx->quit.store(true);
    mpv_wakeup(ctx->mpv);
    if (ctx->eventThread.joinable()) ctx->eventThread.join();

    CGLSetCurrentContext(ctx->gl);
    mpv_render_context_free(ctx->rctx);
    release_surface_locked(ctx);
    CGLSetCurrentContext(nullptr);
    CGLDestroyContext(ctx->gl);

    mpv_terminate_destroy(ctx->mpv);
    delete ctx;
    LOGF("destroyed");
}

} // extern "C"
