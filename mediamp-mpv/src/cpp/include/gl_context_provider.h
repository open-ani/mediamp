/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#ifndef MEDIAMP_GL_CONTEXT_PROVIDER_H
#define MEDIAMP_GL_CONTEXT_PROVIDER_H

// The desktop OpenGL producer path (render_opengl.cpp): GLX on Linux, WGL on Windows.
// Android drives mpv through its own window surface and never compiles it. On Windows
// this path coexists with the D3D11 one (render_d3d11.cpp) in the same binary; which of
// them a player uses is decided by the Skiko render API in use (see MpvSurfaceRing.kt).
#if (defined(__linux__) && !defined(__ANDROID__)) || defined(_WIN32)
#define MEDIAMP_OPENGL_RENDER 1
#endif

#ifdef MEDIAMP_OPENGL_RENDER

#include <cstdint>
#include <string>

namespace mediampv {

/**
 * The OpenGL environment borrowed from Skiko's live redrawer (context A).
 *
 * Both handles stay Skiko-owned for the provider's whole lifetime: the provider never
 * makes share_context current and never destroys native_display. identity is a
 * caller-supplied stable token for that environment — a changed token means the share
 * group must be treated as a new device generation.
 *
 * native_display describes who owns the drawables: an X11 `Display *` on Linux, the
 * redrawer's `HDC` on Windows (used only to copy its pixel format onto the producer's
 * own drawable). screen is the X screen index and is unused on Windows.
 */
struct gl_render_environment final {
    int64_t native_display = 0;
    int64_t share_context = 0;
    int screen = 0;
    uint64_t identity = 0;
};

/**
 * The mediamp producer context (context B), created inside Skiko context A's share
 * group. B owns a private 1x1 drawable and is intentionally usable from one native
 * render thread only. Textures created while B is current are share-group objects; FBOs
 * created there stay B-local and must not be used by the Skiko consumer context.
 */
class gl_context_provider {
public:
    virtual ~gl_context_provider() = default;

    gl_context_provider(const gl_context_provider &) = delete;
    gl_context_provider &operator=(const gl_context_provider &) = delete;

    /** Makes B current on its sole native render thread. */
    virtual bool make_current() = 0;
    /** Clears B from its owner thread before that thread exits. */
    virtual bool clear_current() = 0;
    /** Destroys B and its drawable after the owner thread has stopped. Idempotent. */
    virtual bool destroy() = 0;
    /** Suitable for mpv_opengl_init_params::get_proc_address. */
    virtual void *get_proc_address(const char *name) const = 0;
    virtual std::string last_error() const = 0;

    uint64_t environment_identity() const { return environment_identity_; }

protected:
    explicit gl_context_provider(uint64_t environment_identity)
        : environment_identity_(environment_identity) {}

private:
    const uint64_t environment_identity_;
};

/**
 * Creates the platform producer context for [environment]: GLX on Linux, WGL on Windows.
 * Returns null and fills [error] when the environment cannot be joined; callers must
 * rediscover the live Skiko environment instead of retrying blindly.
 *
 * Must be called on the thread that will own the context (the native render thread): the
 * Windows implementation bootstraps its WGL entry points through a temporary context of
 * its own, which would displace any context already current on the calling thread.
 */
gl_context_provider *create_gl_context_provider(
    const gl_render_environment &environment, std::string *error);

} // namespace mediampv

#endif // MEDIAMP_OPENGL_RENDER

#endif // MEDIAMP_GL_CONTEXT_PROVIDER_H
