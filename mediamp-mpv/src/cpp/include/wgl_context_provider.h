/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#ifndef MEDIAMP_WGL_CONTEXT_PROVIDER_H
#define MEDIAMP_WGL_CONTEXT_PROVIDER_H

#ifdef _WIN32

#include <windows.h>

#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

#include "gl_context_provider.h"

namespace mediampv {

/**
 * Creates the mediamp producer WGL context (context B) in Skiko's share group (context
 * A, `WindowsOpenGLRedrawer`'s HGLRC). The Windows counterpart of
 * [glx_context_provider]: same ownership rules, different drawable.
 *
 * WGL has no pbuffer equivalent that is guaranteed present on every driver, and a
 * context can only be made current against a device context. B therefore owns a private
 * invisible 1x1 window plus its `CS_OWNDC` device context, whose pixel format is copied
 * from Skiko's HDC so the two contexts are share-compatible. The window is never shown
 * and never pumped; it exists purely as the owner of a valid drawable.
 *
 * The window is created and destroyed by the same thread that runs [create] and
 * [destroy] — the native render thread — because `DestroyWindow` only works from the
 * creating thread.
 */
class wgl_context_provider final : public gl_context_provider {
public:
    /**
     * Must run on the thread that will own the context. [create] briefly makes a
     * throwaway context of its own current to resolve `wglCreateContextAttribsARB`, so a
     * caller thread that already has a context current (Skiko's UI thread) would have it
     * displaced.
     */
    static wgl_context_provider *create(
        const gl_render_environment &environment, std::string *error = nullptr);

    ~wgl_context_provider() override;

    bool make_current() override;
    bool clear_current() override;
    bool destroy() override;

    void *get_proc_address(const char *name) const override;

    std::string last_error() const override;

    HGLRC context() const { return context_; }
    HDC device_context() const { return device_context_; }

private:
    wgl_context_provider(
        HWND window, HDC device_context, HGLRC context, uint64_t environment_identity);

    bool fail_locked(const char *message);
    void set_error_locked(const char *message);

    mutable std::mutex mutex_;
    HWND window_ = nullptr;
    HDC device_context_ = nullptr;
    HGLRC context_ = nullptr;
    std::thread::id owner_thread_;
    bool owner_bound_ = false;
    bool current_on_owner_ = false;
    std::string last_error_;
};

} // namespace mediampv

#endif // _WIN32

#endif // MEDIAMP_WGL_CONTEXT_PROVIDER_H
