/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#include "gl_functions.h"

#ifdef MEDIAMP_OPENGL_RENDER

#include <cstdint>
#include <mutex>

#include "log.h"

namespace mediampv {

bool has_current_gl_context() {
#if defined(_WIN32)
    return wglGetCurrentContext() != nullptr;
#else
    return glXGetCurrentContext() != nullptr;
#endif
}

#if defined(_WIN32)

namespace {

void *resolve_gl_symbol(const char *name) {
    if (PROC proc = wglGetProcAddress(name)) {
        // Some drivers report "unsupported" with these sentinels instead of null.
        const auto value = reinterpret_cast<intptr_t>(proc);
        if (value != 1 && value != 2 && value != 3 && value != -1) {
            return reinterpret_cast<void *>(proc);
        }
    }
    static HMODULE const gl_module = LoadLibraryW(L"opengl32.dll");
    return gl_module ? reinterpret_cast<void *>(GetProcAddress(gl_module, name)) : nullptr;
}

/** Core/ARB name first, then the pre-3.0 EXT spelling for compatibility contexts. */
void *resolve_gl_symbol(const char *name, const char *ext_name) {
    if (void *symbol = resolve_gl_symbol(name)) return symbol;
    return resolve_gl_symbol(ext_name);
}

struct framebuffer_functions final {
    PFNGLGENFRAMEBUFFERSPROC gen = nullptr;
    PFNGLDELETEFRAMEBUFFERSPROC destroy = nullptr;
    PFNGLBINDFRAMEBUFFERPROC bind = nullptr;
    PFNGLFRAMEBUFFERTEXTURE2DPROC attach_texture = nullptr;
    PFNGLCHECKFRAMEBUFFERSTATUSPROC check_status = nullptr;
    bool complete = false;
};

const framebuffer_functions &framebuffer_api() {
    static framebuffer_functions functions;
    static std::mutex mutex;
    std::lock_guard<std::mutex> lock(mutex);
    if (functions.complete) return functions;

    // Retried until it succeeds: the first call may happen before any context is current,
    // and wglGetProcAddress resolves nothing then.
    functions.gen = reinterpret_cast<PFNGLGENFRAMEBUFFERSPROC>(
        resolve_gl_symbol("glGenFramebuffers", "glGenFramebuffersEXT"));
    functions.destroy = reinterpret_cast<PFNGLDELETEFRAMEBUFFERSPROC>(
        resolve_gl_symbol("glDeleteFramebuffers", "glDeleteFramebuffersEXT"));
    functions.bind = reinterpret_cast<PFNGLBINDFRAMEBUFFERPROC>(
        resolve_gl_symbol("glBindFramebuffer", "glBindFramebufferEXT"));
    functions.attach_texture = reinterpret_cast<PFNGLFRAMEBUFFERTEXTURE2DPROC>(
        resolve_gl_symbol("glFramebufferTexture2D", "glFramebufferTexture2DEXT"));
    functions.check_status = reinterpret_cast<PFNGLCHECKFRAMEBUFFERSTATUSPROC>(
        resolve_gl_symbol("glCheckFramebufferStatus", "glCheckFramebufferStatusEXT"));
    functions.complete = functions.gen && functions.destroy && functions.bind &&
        functions.attach_texture && functions.check_status;
    if (!functions.complete && has_current_gl_context()) {
        LOGE("this OpenGL driver exposes no framebuffer objects; mpv video cannot be shared");
    }
    return functions;
}

} // namespace

namespace gl {

void gen_framebuffers(GLsizei n, GLuint *framebuffers) {
    const auto &api = framebuffer_api();
    if (api.gen) {
        api.gen(n, framebuffers);
    } else if (framebuffers) {
        for (GLsizei i = 0; i < n; ++i) framebuffers[i] = 0;
    }
}

void delete_framebuffers(GLsizei n, const GLuint *framebuffers) {
    const auto &api = framebuffer_api();
    if (api.destroy) api.destroy(n, framebuffers);
}

void bind_framebuffer(GLenum target, GLuint framebuffer) {
    const auto &api = framebuffer_api();
    if (api.bind) api.bind(target, framebuffer);
}

void framebuffer_texture_2d(
    GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level) {
    const auto &api = framebuffer_api();
    if (api.attach_texture) api.attach_texture(target, attachment, textarget, texture, level);
}

GLenum check_framebuffer_status(GLenum target) {
    const auto &api = framebuffer_api();
    return api.check_status ? api.check_status(target) : 0;
}

} // namespace gl

#endif // _WIN32

} // namespace mediampv

#endif // MEDIAMP_OPENGL_RENDER
