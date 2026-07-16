/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#if defined(__linux__) && !defined(__ANDROID__)

#include <dlfcn.h>

#include <GL/glx.h>
#include <GL/glxext.h>
#include <X11/Xlib.h>

#include <memory>

#include "glx_context_provider.h"
#include "log.h"

namespace {

constexpr int kContextAttributes[] = {
    GLX_CONTEXT_MAJOR_VERSION_ARB, 3,
    GLX_CONTEXT_MINOR_VERSION_ARB, 3,
    GLX_CONTEXT_PROFILE_MASK_ARB, GLX_CONTEXT_CORE_PROFILE_BIT_ARB,
    None,
};

constexpr int kFramebufferAttributes[] = {
    GLX_X_RENDERABLE, True,
    GLX_DRAWABLE_TYPE, GLX_PBUFFER_BIT,
    GLX_RENDER_TYPE, GLX_RGBA_BIT,
    GLX_RED_SIZE, 8,
    GLX_GREEN_SIZE, 8,
    GLX_BLUE_SIZE, 8,
    GLX_ALPHA_SIZE, 8,
    None,
};

constexpr int kPbufferAttributes[] = {
    GLX_PBUFFER_WIDTH, 1,
    GLX_PBUFFER_HEIGHT, 1,
    None,
};

using create_context_attribs_fn = GLXContext (*) (
    Display *, GLXFBConfig, GLXContext, Bool, const int *);

create_context_attribs_fn get_create_context_attribs() {
    return reinterpret_cast<create_context_attribs_fn>(
        glXGetProcAddressARB(reinterpret_cast<const GLubyte *>("glXCreateContextAttribsARB")));
}

} // namespace

namespace mediampv {

glx_context_provider::glx_context_provider(
    Display *display,
    GLXContext context,
    GLXPbuffer drawable,
    uint64_t environment_identity)
    : display_(display),
      context_(context),
      drawable_(drawable),
      environment_identity_(environment_identity) {}

glx_context_provider::~glx_context_provider() {
    destroy();
}

glx_context_provider *glx_context_provider::create(
    const glx_render_environment &environment, std::string *error) {
    if (!environment.display || !environment.share_context) {
        if (error) *error = "GLX render environment requires a live Display and share context";
        return nullptr;
    }
    if (environment.identity == 0) {
        if (error) *error = "GLX render environment identity must be non-zero";
        return nullptr;
    }

    // Skiko and mediamp use the same Xlib Display. Lock it for the complete setup so
    // another thread cannot interleave a GLX request on this connection. We do not call
    // XInitThreads here: it has to run before any Xlib use, which is Skiko's decision.
    XLockDisplay(environment.display);
    int config_count = 0;
    GLXFBConfig *configs = glXChooseFBConfig(
        environment.display, environment.screen, kFramebufferAttributes, &config_count);
    if (!configs || config_count == 0) {
        if (configs) XFree(configs);
        XUnlockDisplay(environment.display);
        if (error) *error = "no GLX RGBA pbuffer framebuffer configuration is available";
        return nullptr;
    }

    int shared_config_id = 0;
    glXQueryContext(
        environment.display, environment.share_context,
        GLX_FBCONFIG_ID, &shared_config_id);
    GLXFBConfig selected_config = configs[0];
    for (int i = 0; i < config_count && shared_config_id != 0; ++i) {
        int candidate_id = 0;
        glXGetFBConfigAttrib(environment.display, configs[i], GLX_FBCONFIG_ID, &candidate_id);
        if (candidate_id == shared_config_id) {
            selected_config = configs[i];
            break;
        }
    }
    GLXPbuffer pbuffer = glXCreatePbuffer(environment.display, selected_config, kPbufferAttributes);
    if (!pbuffer) {
        XFree(configs);
        XUnlockDisplay(environment.display);
        if (error) *error = "glXCreatePbuffer failed";
        return nullptr;
    }

    auto create_context_attribs = get_create_context_attribs();
    if (!create_context_attribs) {
        glXDestroyPbuffer(environment.display, pbuffer);
        XFree(configs);
        XUnlockDisplay(environment.display);
        if (error) *error = "GLX_ARB_create_context is unavailable; OpenGL 3.3 sharing is required";
        return nullptr;
    }

    // The pbuffer is only a drawable for context B. The supplied context A is solely a
    // share-list source and remains current/owned by Skiko on its own render thread.
    GLXContext context = create_context_attribs(
        environment.display, selected_config, environment.share_context, True, kContextAttributes);
    XFree(configs);
    XUnlockDisplay(environment.display);
    if (!context) {
        // GLX context creation failed. The environment might be stale or from another
        // screen/share group; callers must rediscover it instead of retrying blindly.
        XLockDisplay(environment.display);
        glXDestroyPbuffer(environment.display, pbuffer);
        XUnlockDisplay(environment.display);
        if (error) *error = "glXCreateContextAttribsARB(3.3 core, shared) failed";
        return nullptr;
    }

    auto *provider = new glx_context_provider(
        environment.display, context, pbuffer, environment.identity);
    LOGI("created shared GLX producer context=%p pbuffer=%lu environment=%llu",
         static_cast<void *>(context), static_cast<unsigned long>(pbuffer),
         static_cast<unsigned long long>(environment.identity));
    return provider;
}

bool glx_context_provider::make_current() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!context_ || !drawable_) return fail_locked("GLX producer context is destroyed");

    const auto current_thread = std::this_thread::get_id();
    if (owner_bound_ && owner_thread_ != current_thread) {
        return fail_locked("GLX producer context cannot move to another thread");
    }

    XLockDisplay(display_);
    const Bool made_current = glXMakeContextCurrent(display_, drawable_, drawable_, context_);
    XUnlockDisplay(display_);
    if (!made_current) return fail_locked("glXMakeContextCurrent for producer context failed");

    owner_thread_ = current_thread;
    owner_bound_ = true;
    current_on_owner_ = true;
    return true;
}

bool glx_context_provider::clear_current() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!context_) return true;
    if (!owner_bound_ || owner_thread_ != std::this_thread::get_id()) {
        return fail_locked("only the GLX producer owner thread may clear its context");
    }

    XLockDisplay(display_);
    const Bool cleared = glXMakeContextCurrent(display_, None, None, nullptr);
    XUnlockDisplay(display_);
    if (!cleared) return fail_locked("glXMakeContextCurrent clear failed");
    current_on_owner_ = false;
    return true;
}

bool glx_context_provider::destroy() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!context_ && !drawable_) return true;
    if (current_on_owner_) {
        if (owner_thread_ != std::this_thread::get_id() || glXGetCurrentContext() != context_) {
            return fail_locked(
                "GLX producer context is still current; clear it on the render thread before teardown");
        }
        XLockDisplay(display_);
        const Bool cleared = glXMakeContextCurrent(display_, None, None, nullptr);
        XUnlockDisplay(display_);
        if (!cleared) return fail_locked("glXMakeContextCurrent clear during teardown failed");
        current_on_owner_ = false;
    }

    // The native render thread must call clear_current() before it exits. Destruction is
    // intentionally performed only after that thread has joined; GLX cannot safely
    // destroy a context that is still current on another thread.
    XLockDisplay(display_);
    if (context_) glXDestroyContext(display_, context_);
    if (drawable_) glXDestroyPbuffer(display_, drawable_);
    XUnlockDisplay(display_);
    context_ = nullptr;
    drawable_ = 0;
    return true;
}

void *glx_context_provider::get_proc_address(const char *name) const {
    if (!name || !*name) return nullptr;
    if (auto proc = glXGetProcAddressARB(reinterpret_cast<const GLubyte *>(name))) {
        return reinterpret_cast<void *>(proc);
    }
    // Some core entry points are exported by libGL but are intentionally omitted from
    // glXGetProcAddressARB on older GLX implementations.
    static void *const lib_gl = dlopen("libGL.so.1", RTLD_LAZY | RTLD_LOCAL);
    return lib_gl ? dlsym(lib_gl, name) : nullptr;
}

std::string glx_context_provider::last_error() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return last_error_;
}

bool glx_context_provider::fail_locked(const char *message) {
    set_error_locked(message);
    LOGE("%s", message);
    return false;
}

void glx_context_provider::set_error_locked(const char *message) {
    last_error_ = message ? message : "unknown GLX provider error";
}

} // namespace mediampv

#endif // defined(__linux__) && !defined(__ANDROID__)
