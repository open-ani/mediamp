/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#ifndef MEDIAMP_GLX_CONTEXT_PROVIDER_H
#define MEDIAMP_GLX_CONTEXT_PROVIDER_H

#ifdef __linux__

#include <GL/glx.h>

#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

namespace mediampv {

/**
 * The OpenGL environment borrowed from Skiko's live Linux OpenGL redrawer.
 *
 * display and share_context remain Skiko-owned for the provider's whole lifetime. The
 * provider never makes share_context current and never closes display. identity is a
 * caller-supplied stable token for the Skiko GLX environment: a changed token means the
 * share group must be treated as a new device generation.
 */
struct glx_render_environment final {
    Display *display = nullptr;
    GLXContext share_context = nullptr;
    int screen = 0;
    uint64_t identity = 0;
};

/**
 * Creates the mediamp producer GLX context (context B) in Skiko's share group (context
 * A). Context B has a private 1x1 pbuffer and is intentionally usable only from one
 * native render thread. Textures created while B is current are share-group objects;
 * FBOs created there stay B-local and must not be used by the Skiko consumer context.
 */
class glx_context_provider final {
public:
    static glx_context_provider *create(
        const glx_render_environment &environment, std::string *error = nullptr);

    ~glx_context_provider();

    glx_context_provider(const glx_context_provider &) = delete;
    glx_context_provider &operator=(const glx_context_provider &) = delete;

    /** Makes B current on its sole native render thread. */
    bool make_current();
    /** Clears B from its owner thread before that thread exits. */
    bool clear_current();
    /** Destroys B and its pbuffer after the owner thread has stopped. Idempotent. */
    bool destroy();

    /** Suitable for mpv_opengl_init_params::get_proc_address. */
    void *get_proc_address(const char *name) const;

    uint64_t environment_identity() const { return environment_identity_; }
    GLXContext context() const { return context_; }
    GLXPbuffer drawable() const { return drawable_; }
    std::string last_error() const;

private:
    glx_context_provider(
        Display *display,
        GLXContext context,
        GLXPbuffer drawable,
        uint64_t environment_identity);

    bool fail_locked(const char *message);
    void set_error_locked(const char *message);

    mutable std::mutex mutex_;
    Display *display_ = nullptr;       // borrowed from Skiko; never XCloseDisplay'd here
    GLXContext context_ = nullptr;
    GLXPbuffer drawable_ = 0;
    uint64_t environment_identity_ = 0;
    std::thread::id owner_thread_;
    bool owner_bound_ = false;
    bool current_on_owner_ = false;
    std::string last_error_;
};

} // namespace mediampv

#endif // __linux__

#endif // MEDIAMP_GLX_CONTEXT_PROVIDER_H
