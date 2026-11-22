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

struct wgl_drawable;

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
 * The window lives on its own message-pump thread, blocked in GetMessageW — NOT on the
 * render thread. A top-level window whose thread does not retrieve messages deadlocks
 * the process: window activation/focus bookkeeping (e.g. when the application's main
 * window closes) makes other in-process threads SendMessage to every top-level window,
 * and a cross-thread sent message is only delivered while the owning thread is inside
 * message retrieval. The render thread spends its life in condition waits, mpv
 * rendering and JNI upcalls, so it must not own a window; the pump thread services it
 * at all times. Window and GDI operations (creation, pixel format) all
 * happen on the pump thread; the render thread only creates, uses and deletes the WGL
 * context (HDCs and HGLRCs are not thread-affine — a context is simply current on one
 * thread at a time).
 *
 * The window and its pump thread are never destroyed. destroy() deletes the context and
 * parks the window for the next create() in this process. DestroyWindow on a window with
 * a pixel format runs the OpenGL driver's own window hook, and NVIDIA's (576.02) creates
 * a D3D11 device there while holding a driver lock, which needs the loader lock. A thread
 * that exits at that moment holds the loader lock and waits for the same driver lock in
 * the driver's DLL_THREAD_DETACH. The process then deadlocks with the loader lock taken:
 * no thread can start or exit and no library can load afterwards. Player teardown is
 * exactly when other threads exit (mpv's workers, the JVM's), and on a hybrid GPU laptop
 * about one in eight teardowns of this fallback ended that way. Never destroying the
 * window keeps the driver out of that hook. The cost is one idle 1x1 window and thread
 * per fallback player that was open at the same time, kept until the process ends.
 *
 * Thread affinity: create() must run on the thread that will use the context (the
 * render thread). It leaves the context current on success. destroy() must run on that
 * same thread.
 */
class wgl_offscreen_context final {
public:
    /**
     * Takes a parked window, or starts a window thread (which creates the hidden window
     * and picks a hardware-accelerated (ICD) pixel format), then creates the context on
     * the calling thread, preferring a 3.3 core profile through
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

    /**
     * Releases and deletes the context from the calling thread and parks the window for
     * reuse. The window is not destroyed, see the class comment.
     */
    void destroy();

    /**
     * Resolves a GL entry point for mpv: wglGetProcAddress first (filtering the
     * non-null "unsupported" sentinels some drivers return), then opengl32.dll for
     * the OpenGL 1.1 core that wglGetProcAddress deliberately does not resolve.
     */
    void *get_proc_address(const char *name) const;

    wgl_offscreen_context(const wgl_offscreen_context &) = delete;
    wgl_offscreen_context &operator=(const wgl_offscreen_context &) = delete;

private:
    wgl_offscreen_context() = default;

    // The hidden window this context draws to, held from create() until destroy().
    wgl_drawable *drawable_ = nullptr;
    HGLRC context_ = nullptr; // owned by the render thread
};

} // namespace mediampv

#endif // _WIN32

#endif // MEDIAMP_WGL_OFFSCREEN_CONTEXT_H
