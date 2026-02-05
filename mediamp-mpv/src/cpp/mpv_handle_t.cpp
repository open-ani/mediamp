#include <iostream>
#include "mpv_handle_t.h"
#include "method_cache.h"
#include "compatible_thread.h"
#include "global_lock.h"
#include <mpv/render_gl.h>

#ifdef _WIN32
#include <windows.h>
#include <gl/GL.h>
#endif

#if defined(__linux__) && !defined(__ANDROID__)
#include <GL/glx.h>
#endif

#ifdef __ANDROID__
extern "C" {
#include <libavcodec/jni.h>
}
#endif

#define CHECK_HANDLE() if (!handle_) { \
    LOG("mpv handle is not created when %s", __FUNCTION__); \
    return false; \
}
#define CHECK_HANDLE_RETURN_INT() if (!handle_) { \
    LOG("mpv handle is not created when %s", __FUNCTION__); \
    return 0; \
}

namespace mediampv {

#ifdef _WIN32
bool release_texture_impl(GLuint* texture_id, GLuint* framebuffer_object);
static void* get_proc_address_mpv(void* ctx, const char* name);

#define GL_FRAMEBUFFER            0x8D40
#define GL_COLOR_ATTACHMENT0      0x8CE0
#define GL_RGBA8                  0x8058
#define GL_FRAMEBUFFER_COMPLETE   0x8CD5
typedef void (APIENTRY *PFNGLGENFRAMEBUFFERSPROC)(GLsizei n, GLuint *framebuffers);
typedef void (APIENTRY *PFNGLBINDFRAMEBUFFERPROC)(GLenum target, GLuint framebuffer);
typedef void (APIENTRY *PFNGLFRAMEBUFFERTEXTURE2DPROC)(GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level);
typedef void (APIENTRY *PFNGLDELETEFRAMEBUFFERSPROC)(GLsizei n, const GLuint *framebuffers);
typedef GLenum (APIENTRY *PFNGLCHECKFRAMEBUFFERSTATUSPROC)(GLenum target);

static PFNGLGENFRAMEBUFFERSPROC pfnGlGenFramebuffers = nullptr;
static PFNGLBINDFRAMEBUFFERPROC pfnGlBindFramebuffer = nullptr;
static PFNGLFRAMEBUFFERTEXTURE2DPROC pfnGlFramebufferTexture2D = nullptr;
static PFNGLDELETEFRAMEBUFFERSPROC pfnGlDeleteFramebuffers = nullptr;
static PFNGLCHECKFRAMEBUFFERSTATUSPROC pfnGlCheckFramebufferStatus = nullptr;

static bool gl_functions_loaded = false;
static bool load_gl_functions() {
    if (gl_functions_loaded) return true;
    pfnGlGenFramebuffers = (PFNGLGENFRAMEBUFFERSPROC)get_proc_address_mpv(nullptr, "glGenFramebuffers");
    pfnGlBindFramebuffer = (PFNGLBINDFRAMEBUFFERPROC)get_proc_address_mpv(nullptr, "glBindFramebuffer");
    pfnGlFramebufferTexture2D = (PFNGLFRAMEBUFFERTEXTURE2DPROC)get_proc_address_mpv(nullptr, "glFramebufferTexture2D");
    pfnGlDeleteFramebuffers = (PFNGLDELETEFRAMEBUFFERSPROC)get_proc_address_mpv(nullptr, "glDeleteFramebuffers");
    pfnGlCheckFramebufferStatus = (PFNGLCHECKFRAMEBUFFERSTATUSPROC)get_proc_address_mpv(nullptr, "glCheckFramebufferStatus");
    gl_functions_loaded = pfnGlGenFramebuffers && pfnGlBindFramebuffer &&
                          pfnGlFramebufferTexture2D && pfnGlDeleteFramebuffers &&
                          pfnGlCheckFramebufferStatus;
    return gl_functions_loaded;
}

#endif

#if defined(__linux__) && !defined(__ANDROID__)
bool release_texture_impl(GLuint* texture_id, GLuint* framebuffer_object);
static void* get_proc_address_mpv(void* ctx, const char* name);

#ifndef GL_FRAMEBUFFER
#define GL_FRAMEBUFFER            0x8D40
#endif
#ifndef GL_COLOR_ATTACHMENT0
#define GL_COLOR_ATTACHMENT0      0x8CE0
#endif
#ifndef GL_RGBA8
#define GL_RGBA8                  0x8058
#endif
#ifndef GL_FRAMEBUFFER_COMPLETE
#define GL_FRAMEBUFFER_COMPLETE   0x8CD5
#endif
typedef void (APIENTRY *PFNGLGENFRAMEBUFFERSPROC)(GLsizei n, GLuint *framebuffers);
typedef void (APIENTRY *PFNGLBINDFRAMEBUFFERPROC)(GLenum target, GLuint framebuffer);
typedef void (APIENTRY *PFNGLFRAMEBUFFERTEXTURE2DPROC)(GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level);
typedef void (APIENTRY *PFNGLDELETEFRAMEBUFFERSPROC)(GLsizei n, const GLuint *framebuffers);
typedef GLenum (APIENTRY *PFNGLCHECKFRAMEBUFFERSTATUSPROC)(GLenum target);

static PFNGLGENFRAMEBUFFERSPROC pfnGlGenFramebuffers = nullptr;
static PFNGLBINDFRAMEBUFFERPROC pfnGlBindFramebuffer = nullptr;
static PFNGLFRAMEBUFFERTEXTURE2DPROC pfnGlFramebufferTexture2D = nullptr;
static PFNGLDELETEFRAMEBUFFERSPROC pfnGlDeleteFramebuffers = nullptr;
static PFNGLCHECKFRAMEBUFFERSTATUSPROC pfnGlCheckFramebufferStatus = nullptr;

static bool gl_functions_loaded = false;
static bool load_gl_functions() {
    if (gl_functions_loaded) return true;
    pfnGlGenFramebuffers = (PFNGLGENFRAMEBUFFERSPROC)get_proc_address_mpv(nullptr, "glGenFramebuffers");
    pfnGlBindFramebuffer = (PFNGLBINDFRAMEBUFFERPROC)get_proc_address_mpv(nullptr, "glBindFramebuffer");
    pfnGlFramebufferTexture2D = (PFNGLFRAMEBUFFERTEXTURE2DPROC)get_proc_address_mpv(nullptr, "glFramebufferTexture2D");
    pfnGlDeleteFramebuffers = (PFNGLDELETEFRAMEBUFFERSPROC)get_proc_address_mpv(nullptr, "glDeleteFramebuffers");
    pfnGlCheckFramebufferStatus = (PFNGLCHECKFRAMEBUFFERSTATUSPROC)get_proc_address_mpv(nullptr, "glCheckFramebufferStatus");
    gl_functions_loaded = pfnGlGenFramebuffers && pfnGlBindFramebuffer &&
                          pfnGlFramebufferTexture2D && pfnGlDeleteFramebuffers &&
                          pfnGlCheckFramebufferStatus;
    return gl_functions_loaded;
}

static void* get_proc_address_mpv(void* ctx, const char* name) {
    return reinterpret_cast<void*>(glXGetProcAddressARB(reinterpret_cast<const GLubyte*>(name)));
}

#endif

CREATE_LOCK(global_guard);
JavaVM *global_jvm = nullptr;

void mpv_handle_t::create(JNIEnv *env, jobject app_context) {
    FP;
    LOCK(global_guard);

    if (!global_jvm) {
        env->GetJavaVM(&global_jvm);
        if (!global_jvm) {
            LOG("failed to get current jvm");
            exit(1); // TODO: don't exit
        }

#ifdef __ANDROID__
        av_jni_set_java_vm(global_jvm, nullptr);
#endif
    }

    jvm_ = global_jvm;
    handle_ = mpv_create();

    // use terminal log level but request verbose messages
    // this way --msg-level can be used to adjust later
    mpv_request_log_messages(handle_, "terminal-default");
    mpv_set_option_string(handle_, "msg-level", "all=v");
}

bool mpv_handle_t::initialize() {
    FP;

    if (!handle_) return false;
    if (mpv_initialize(handle_) < 0) {
        LOG("failed to initialize mpv");
        return false;
    }

    event_thread_ = std::make_shared<mediampv::compatible_thread>([&] { event_loop(nullptr); });
    if (!event_thread_->create()) {
        LOG("failed to create event thread");
        return false;
    }

    return true;
}

bool mpv_handle_t::set_event_listener(JNIEnv *env, jobject listener) {
    FP;

    if (event_listener_ && *event_listener_) {
        env->DeleteGlobalRef(*event_listener_);
        event_listener_ = nullptr;
    }
    mediampv::jni_cache_classes(env);

    if (env->IsInstanceOf(listener, mediampv::jni_mediamp_clazz_EventListener) != JNI_TRUE) {
        LOG("listener is not an instance of EventListener");
        return false;
    }

    if (!event_listener_) event_listener_ = new jobject;
    *event_listener_ = env->NewGlobalRef(listener);

    return true;
}

bool mpv_handle_t::command(const char **args) {
    FP;
    CHECK_HANDLE()
    return mpv_command(handle_, args) >= 0;
}

bool mpv_handle_t::set_option(const char *key, const char *value) {
    FP;
    CHECK_HANDLE()
    return mpv_set_option_string(handle_, key, value);
}

bool mpv_handle_t::get_property(const char *name, mpv_format format, void *out_result) {
    FP;
    CHECK_HANDLE()
    return mpv_get_property(handle_, name, format, out_result) >= 0;
}

bool mpv_handle_t::set_property(const char *name, mpv_format format, void *in_value) {
    FP;
    CHECK_HANDLE()
    return mpv_set_property(handle_, name, format, in_value) >= 0;
}

bool mpv_handle_t::observe_property(const char *property, mpv_format format, uint64_t reply_data) {
    FP;
    CHECK_HANDLE()
    return mpv_observe_property(handle_, reply_data, property, format) >= 0;
}

bool mpv_handle_t::unobserve_property(uint64_t reply_data) {
    FP;
    CHECK_HANDLE()
    return mpv_unobserve_property(handle_, reply_data) >= 0;
}

CREATE_LOCK(surface_access_lock);

bool mpv_handle_t::attach_android_surface(JNIEnv *env, jobject surface) {
    FP;
    LOCK(surface_access_lock);
    CHECK_HANDLE()

#ifdef __ANDROID__
    if (surface_attached_) detach_android_surface(env);
    if (env->IsInstanceOf(surface, mediampv::jni_mediamp_clazz_android_Surface) != JNI_TRUE) {
        LOG("surface is not instance of android.view.Surface");
        return false;
    }

    jobject ref = env->NewGlobalRef(surface);
    int64_t wid = (int64_t)(intptr_t) ref;
    surface_ = ref;
    surface_attached_ = mpv_set_option(handle_, "wid", MPV_FORMAT_INT64, &wid) >= 0;

    return surface_attached_;
#else
    LOG("attach_android_surface is only implemented on Android");
    return false;
#endif
}

bool mpv_handle_t::detach_android_surface(JNIEnv *env) {
    FP;
    LOCK(surface_access_lock);
    CHECK_HANDLE()

#ifdef __ANDROID__
    if (!surface_attached_) return false;

    int64_t wid = 0;
    bool result = mpv_set_option(handle_, "wid", MPV_FORMAT_INT64, (void*) &wid);
    env->DeleteGlobalRef(surface_);
    surface_attached_ = false;

    return result;
#else
    LOG("detach_android_surface is only implemented on Android");
    return false;
#endif
}

#ifdef __ANDROID__
bool mpv_handle_t::attach_window_surface(int64_t wid) {
    FP;
    CHECK_HANDLE();
    return mpv_set_option(handle_, "wid", MPV_FORMAT_INT64, &wid) >= 0;
}

bool mpv_handle_t::detach_window_surface() {
    FP;
    CHECK_HANDLE();
    int64_t wid = 0;
    return mpv_set_option(handle_, "wid", MPV_FORMAT_INT64, &wid) >= 0;
}
#endif

#ifdef _WIN32
bool mpv_handle_t::create_render_context(HDC device, HGLRC context) {
    FP;
    CHECK_HANDLE()

    if (render_context_)
        return true;

    device_ = device;
    context_ = context;

    HDC old_dc = wglGetCurrentDC();
    HGLRC old_ctx = wglGetCurrentContext();
    wglMakeCurrent(device_, context_);

    if (!load_gl_functions()) {
        LOG("Failed to load OpenGL functions");
        wglMakeCurrent(old_dc, old_ctx);
        return false;
    }

    mpv_opengl_init_params gl_init_params{
            .get_proc_address = get_proc_address_mpv,
            .get_proc_address_ctx = nullptr
    };
    mpv_render_param params[] = {
            {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
            {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init_params},
            {MPV_RENDER_PARAM_INVALID, nullptr},
    };

    if (mpv_render_context_create(&render_context_, handle_, params) < 0) {
        render_context_ = nullptr;
        return false;
    }

    wglMakeCurrent(old_dc, old_ctx);

    return true;
}

static void* get_proc_address_mpv(void* ctx, const char* name) {
    void* addr = (void*)wglGetProcAddress(name);
    if (addr == nullptr || (reinterpret_cast<intptr_t>(addr) >= -1 && reinterpret_cast<intptr_t>(addr) <= 3)) {
        static HMODULE opengl32 = LoadLibraryA("opengl32.dll");
        if (opengl32) {
            addr = (void*)GetProcAddress(opengl32, name);
        }
    }
    return addr;
}

bool mpv_handle_t::destroy_render_context() {
    FP;
    CHECK_HANDLE()

    if (!render_context_)
        return false;

    HDC old_dc = wglGetCurrentDC();
    HGLRC old_ctx = wglGetCurrentContext();
    wglMakeCurrent(device_, context_);

    mpv_render_context_free(render_context_);

    wglMakeCurrent(old_dc, old_ctx);

    render_context_ = nullptr;
    return true;
}

GLuint mpv_handle_t::create_texture(int width, int height) {
    FP;
    CHECK_HANDLE_RETURN_INT()
    LOCK(texture_lock);

    HDC old_dc = wglGetCurrentDC();
    HGLRC old_ctx = wglGetCurrentContext();
    wglMakeCurrent(device_, context_);

    if (texture_ != GL_ZERO && fbo_ != GL_ZERO) {
        width_ = 0;
        height_ = 0;
        release_texture_impl(&texture_, &fbo_);
    }

    glGenTextures(1, &texture_);
    glBindTexture(GL_TEXTURE_2D, texture_);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, nullptr);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    pfnGlGenFramebuffers(1, &fbo_);
    pfnGlBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    pfnGlFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, texture_, 0);

    wglMakeCurrent(old_dc, old_ctx);

    width_ = width;
    height_ = height;

    return texture_;
}

bool mpv_handle_t::release_texture() {
    FP;
    CHECK_HANDLE()
    LOCK(texture_lock);

    width_ = 0;
    height_ = 0;


    HDC old_dc = wglGetCurrentDC();
    HGLRC old_ctx = wglGetCurrentContext();
    wglMakeCurrent(device_, context_);

    bool released = release_texture_impl(&texture_, &fbo_);

    wglMakeCurrent(old_dc, old_ctx);

    return released;
}

bool release_texture_impl(GLuint* texture_id, GLuint* framebuffer_object) {
    if (*texture_id == GL_ZERO || *framebuffer_object == GL_ZERO) return false;

    GLuint textures_to_delete[1] = {*texture_id};
    GLuint framebuffer_to_delete[1] = {*framebuffer_object};

    glDeleteTextures(1, textures_to_delete);
    pfnGlDeleteFramebuffers(1, framebuffer_to_delete);

    *texture_id = GL_ZERO;
    *framebuffer_object = GL_ZERO;
    return true;
}

bool mpv_handle_t::render_frame() {
    CHECK_HANDLE()
    LOCK(texture_lock);

    if (!render_context_ || !context_ || !device_ || !fbo_ || !texture_ || !width_ || !height_)
        return false;

    HDC old_dc = wglGetCurrentDC();
    HGLRC old_ctx = wglGetCurrentContext();
    if (!wglMakeCurrent(device_, context_)) {
        LOG("Failed to make OpenGL context current in render_frame");
        return false;
    }

    // 绑定 FBO 并检查状态
    pfnGlBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    GLenum status = pfnGlCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOG("Framebuffer not complete: 0x%x", status);
        wglMakeCurrent(old_dc, old_ctx);
        return false;
    }

    mpv_opengl_fbo fbo_params{static_cast<int>(fbo_), width_, height_, GL_RGBA8};
    mpv_render_param params[] = {
            {MPV_RENDER_PARAM_OPENGL_FBO, &fbo_params},
            {MPV_RENDER_PARAM_INVALID, nullptr},
    };

    // 无论是否有新帧，都调用 render（mpv 文档建议）
    int render_result = mpv_render_context_render(render_context_, params);
    if (render_result < 0) {
        LOG("mpv_render_context_render failed: %d", render_result);
    }

    // 解绑 FBO
    pfnGlBindFramebuffer(GL_FRAMEBUFFER, 0);

    glFinish();
    wglMakeCurrent(old_dc, old_ctx);

    return render_result >= 0;
}

#endif

#if defined(__linux__) && !defined(__ANDROID__)
bool mpv_handle_t::create_render_context(Display* display, GLXContext context) {
    FP;
    CHECK_HANDLE()
    (void)display;
    (void)context;

    if (render_context_)
        return true;

    Display* current_display = glXGetCurrentDisplay();
    GLXContext current_context = glXGetCurrentContext();
    GLXDrawable current_drawable = glXGetCurrentDrawable();

    if (!current_display || !current_context || !current_drawable) {
        LOG("No current GLX context/display/drawable");
        return false;
    }

    display_ = current_display;
    context_ = current_context;
    drawable_ = current_drawable;

    if (!load_gl_functions()) {
        LOG("Failed to load OpenGL functions");
        return false;
    }

    mpv_opengl_init_params gl_init_params{
            .get_proc_address = get_proc_address_mpv,
            .get_proc_address_ctx = nullptr
    };
    mpv_render_param params[] = {
            {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
            {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init_params},
            {MPV_RENDER_PARAM_X11_DISPLAY, display_},
            {MPV_RENDER_PARAM_INVALID, nullptr},
    };

    if (mpv_render_context_create(&render_context_, handle_, params) < 0) {
        render_context_ = nullptr;
        return false;
    }

    return true;
}

bool mpv_handle_t::destroy_render_context() {
    FP;
    CHECK_HANDLE()

    if (!render_context_)
        return false;

    Display* old_display = glXGetCurrentDisplay();
    GLXContext old_ctx = glXGetCurrentContext();
    GLXDrawable old_drawable = glXGetCurrentDrawable();

    if (display_ && context_ && drawable_) {
        glXMakeCurrent(display_, drawable_, context_);
    }

    mpv_render_context_free(render_context_);

    if (old_display && old_ctx) {
        glXMakeCurrent(old_display, old_drawable, old_ctx);
    } else if (display_) {
        glXMakeCurrent(display_, 0, nullptr);
    }

    render_context_ = nullptr;
    return true;
}

GLuint mpv_handle_t::create_texture(int width, int height) {
    FP;
    CHECK_HANDLE_RETURN_INT()
    LOCK(texture_lock);

    if (!display_ || !context_) return 0;
    if (glXGetCurrentContext() != context_) {
        LOG("OpenGL context is not current in create_texture");
        return 0;
    }

    if (texture_ != GL_ZERO && fbo_ != GL_ZERO) {
        width_ = 0;
        height_ = 0;
        release_texture_impl(&texture_, &fbo_);
    }

    glGenTextures(1, &texture_);
    glBindTexture(GL_TEXTURE_2D, texture_);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, nullptr);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    pfnGlGenFramebuffers(1, &fbo_);
    pfnGlBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    pfnGlFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, texture_, 0);

    width_ = width;
    height_ = height;

    return texture_;
}

bool mpv_handle_t::release_texture() {
    FP;
    CHECK_HANDLE()
    LOCK(texture_lock);

    width_ = 0;
    height_ = 0;

    if (!display_ || !context_) return false;
    if (glXGetCurrentContext() != context_) {
        LOG("OpenGL context is not current in release_texture");
        return false;
    }

    bool released = release_texture_impl(&texture_, &fbo_);

    return released;
}

bool release_texture_impl(GLuint* texture_id, GLuint* framebuffer_object) {
    if (*texture_id == GL_ZERO || *framebuffer_object == GL_ZERO) return false;

    GLuint textures_to_delete[1] = {*texture_id};
    GLuint framebuffer_to_delete[1] = {*framebuffer_object};

    glDeleteTextures(1, textures_to_delete);
    pfnGlDeleteFramebuffers(1, framebuffer_to_delete);

    *texture_id = GL_ZERO;
    *framebuffer_object = GL_ZERO;
    return true;
}

bool mpv_handle_t::render_frame() {
    CHECK_HANDLE()
    LOCK(texture_lock);

    if (!render_context_ || !context_ || !display_ || !fbo_ || !texture_ || !width_ || !height_)
        return false;

    if (glXGetCurrentContext() != context_) {
        LOG("OpenGL context is not current in render_frame");
        return false;
    }

    pfnGlBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    GLenum status = pfnGlCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOG("Framebuffer not complete: 0x%x", status);
        return false;
    }

    mpv_opengl_fbo fbo_params{static_cast<int>(fbo_), width_, height_, GL_RGBA8};
    mpv_render_param params[] = {
            {MPV_RENDER_PARAM_OPENGL_FBO, &fbo_params},
            {MPV_RENDER_PARAM_INVALID, nullptr},
    };

    int render_result = mpv_render_context_render(render_context_, params);
    if (render_result < 0) {
        LOG("mpv_render_context_render failed: %d", render_result);
    }

    pfnGlBindFramebuffer(GL_FRAMEBUFFER, 0);

    glFinish();

    return render_result >= 0;
}

#endif

bool mpv_handle_t::destroy(JNIEnv *env) {
    FP;
    CHECK_HANDLE()

    event_loop_request_exit = true;
    mpv_wakeup(handle_);

    if (!event_thread_) {
        LOG("event thread is not created when destroy mpv handle");
        return false;
    }
    event_thread_->join();

    if (event_listener_) env->DeleteGlobalRef(*event_listener_);
    mpv_terminate_destroy(handle_);

    return true;
}

} // namespace mediampv
