/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#ifndef MEDIAMP_GL_FUNCTIONS_H
#define MEDIAMP_GL_FUNCTIONS_H

#include "gl_context_provider.h"

#ifdef MEDIAMP_OPENGL_RENDER

// The GL headers the desktop OpenGL render path compiles against. libGL exports the
// full core on Linux, so the prototypes can be used directly; opengl32.dll exports only
// OpenGL 1.1, so on Windows glext.h contributes types and enums while the post-1.1 entry
// points are resolved at runtime (see below).
#if defined(_WIN32)
#include <windows.h>
#include <GL/gl.h>
#include <GL/glext.h>
#else
#define GL_GLEXT_PROTOTYPES 1
#include <GL/gl.h>
#include <GL/glext.h>
#include <GL/glx.h>
#endif

// Enums newer than OpenGL 1.1 that the render path uses. glext.h defines all of them;
// the guards only protect against an outdated copy of that header in a MinGW sysroot.
#ifndef GL_CLAMP_TO_EDGE
#define GL_CLAMP_TO_EDGE 0x812F
#endif
#ifndef GL_FRAMEBUFFER
#define GL_FRAMEBUFFER 0x8D40
#endif
#ifndef GL_FRAMEBUFFER_BINDING
#define GL_FRAMEBUFFER_BINDING 0x8CA6
#endif
#ifndef GL_COLOR_ATTACHMENT0
#define GL_COLOR_ATTACHMENT0 0x8CE0
#endif
#ifndef GL_FRAMEBUFFER_COMPLETE
#define GL_FRAMEBUFFER_COMPLETE 0x8CD5
#endif

namespace mediampv {

/**
 * Whether an OpenGL context is current on the calling thread. The consumer-side JNI
 * helpers require Skiko's context to be current, and calling GL without one is undefined
 * rather than merely unsuccessful.
 */
bool has_current_gl_context();

#if defined(_WIN32)

namespace gl {

/**
 * Framebuffer-object entry points resolved through `wglGetProcAddress`, aliased to their
 * standard names below — the same indirection GLEW/glad apply, so call sites keep the
 * spelling they have on Linux.
 *
 * Resolution happens on first use and is cached process-wide. The producer context and
 * Skiko's consumer context come from the same ICD, so a single table is valid for both,
 * and the pointers are only ever called while one of them is current. A call made with
 * no context current (or on a driver without framebuffer objects) is a no-op that leaves
 * the out-parameters zeroed, which every call site already treats as failure.
 */
void gen_framebuffers(GLsizei n, GLuint *framebuffers);
void delete_framebuffers(GLsizei n, const GLuint *framebuffers);
void bind_framebuffer(GLenum target, GLuint framebuffer);
void framebuffer_texture_2d(
    GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level);
GLenum check_framebuffer_status(GLenum target);

} // namespace gl

#endif // _WIN32

} // namespace mediampv

#if defined(_WIN32)
#define glGenFramebuffers ::mediampv::gl::gen_framebuffers
#define glDeleteFramebuffers ::mediampv::gl::delete_framebuffers
#define glBindFramebuffer ::mediampv::gl::bind_framebuffer
#define glFramebufferTexture2D ::mediampv::gl::framebuffer_texture_2d
#define glCheckFramebufferStatus ::mediampv::gl::check_framebuffer_status
#endif

#endif // MEDIAMP_OPENGL_RENDER

#endif // MEDIAMP_GL_FUNCTIONS_H
