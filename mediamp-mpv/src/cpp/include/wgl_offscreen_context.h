/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#ifndef MEDIAMP_WGL_OFFSCREEN_CONTEXT_H
#define MEDIAMP_WGL_OFFSCREEN_CONTEXT_H

#ifdef _WIN32

#include <windows.h>

#include <string>

namespace mediampv {

/**
 * A private OpenGL context for the Windows OpenGL fallback producer
 * (render_opengl_win.cpp). Deliberately independent: it shares nothing with Skiko's
 * context — no wglShareLists, no pixel-format matching — because frames leave this
 * context through a CPU readback, never as GL objects. That independence is the whole
 * point of the fallback: it cannot break when the driver refuses cross-context sharing.
 *
 * The drawable is a hidden 1x1 CS_OWNDC window: WGL has no universally available
 * pbuffer/surfaceless equivalent and a context needs a DC to be created and made
 * current. Rendering only ever targets FBOs; the window's framebuffer is never drawn
 * to and never presented.
 *
 * Thread affinity: create() must run on the thread that will use the context (the
 * render thread). It leaves the context current on success. destroy() must run on that
 * same thread — DestroyWindow only works on the creating thread, and wglDeleteContext
 * requires the context to be current nowhere.
 */
class wgl_offscreen_context final {
public:
    /**
     * Creates the hidden window, picks a hardware-accelerated (ICD) pixel format,
     * and creates the context — preferring a 3.3 core profile through
     * wglCreateContextAttribsARB with a legacy wglCreateContext fallback. On success
     * the context is current on the calling thread. Returns null with *error set on
     * failure.
     *
     * ChoosePixelFormat is only trusted when the format it returns is accelerated:
     * it matches a PIXELFORMATDESCRIPTOR, which cannot express most of what
     * distinguishes real formats, and it readily returns the Microsoft generic
     * (software, GL 1.1) format — on which wglCreateContext still succeeds but mpv
     * cannot run. When that happens the format list is scanned for the first
     * accelerated RGBA window format instead.
     */
    static wgl_offscreen_context *create(std::string *error);

    ~wgl_offscreen_context();

    /** Releases the context from the calling thread and destroys it with its window. */
    void destroy();

    /**
     * Resolves a GL entry point for mpv: wglGetProcAddress first (filtering the
     * non-null "unsupported" sentinels some drivers return), then opengl32.dll for
     * the OpenGL 1.1 core that wglGetProcAddress deliberately does not resolve.
     */
    void *get_proc_address(const char *name) const;

    /**
     * Discards queued window messages. The hidden window is top-level, so it receives
     * posted system broadcasts, and its thread never runs a message pump — the render
     * loop calls this on every wake-up so the queue cannot grow without bound.
     */
    void drain_window_messages();

    wgl_offscreen_context(const wgl_offscreen_context &) = delete;
    wgl_offscreen_context &operator=(const wgl_offscreen_context &) = delete;

private:
    wgl_offscreen_context(HWND window, HDC device_context, HGLRC context)
        : window_(window), device_context_(device_context), context_(context) {}

    HWND window_ = nullptr;
    HDC device_context_ = nullptr;
    HGLRC context_ = nullptr;
};

} // namespace mediampv

#endif // _WIN32

#endif // MEDIAMP_WGL_OFFSCREEN_CONTEXT_H
