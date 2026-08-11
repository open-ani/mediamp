/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#ifndef MEDIAMP_GL_FUNCTIONS_WIN_H
#define MEDIAMP_GL_FUNCTIONS_WIN_H

#ifdef _WIN32

// opengl32.dll exports only the OpenGL 1.1 core, so glext.h contributes types and enums
// while the post-1.1 entry points below are resolved at runtime through
// wglGetProcAddress.
#include <windows.h>
#include <GL/gl.h>
#include <GL/glext.h>

// Enums newer than OpenGL 1.1 that the fallback render path uses. glext.h defines all
// of them; the guards only protect against an outdated copy in a MinGW sysroot.
#ifndef GL_CLAMP_TO_EDGE
#define GL_CLAMP_TO_EDGE 0x812F
#endif
#ifndef GL_FRAMEBUFFER
#define GL_FRAMEBUFFER 0x8D40
#endif
#ifndef GL_COLOR_ATTACHMENT0
#define GL_COLOR_ATTACHMENT0 0x8CE0
#endif
#ifndef GL_FRAMEBUFFER_COMPLETE
#define GL_FRAMEBUFFER_COMPLETE 0x8CD5
#endif
#ifndef GL_RGBA8
#define GL_RGBA8 0x8058
#endif
#ifndef GL_PIXEL_PACK_BUFFER
#define GL_PIXEL_PACK_BUFFER 0x88EB
#endif
#ifndef GL_STREAM_READ
#define GL_STREAM_READ 0x88E1
#endif
#ifndef GL_READ_ONLY
#define GL_READ_ONLY 0x88B8
#endif

namespace mediampv {
namespace gl {

/**
 * Framebuffer-object entry points resolved through wglGetProcAddress — core/ARB name
 * first, then the pre-3.0 EXT spelling for old compatibility contexts. Resolution
 * happens on first use and is cached process-wide; it requires a GL context to be
 * current, which the render thread guarantees (the offscreen WGL context is made
 * current before any of these run). A call on a driver without framebuffer objects is
 * a no-op that leaves out-parameters zeroed, which every call site treats as failure.
 */
void gen_framebuffers(GLsizei n, GLuint *framebuffers);
void delete_framebuffers(GLsizei n, const GLuint *framebuffers);
void bind_framebuffer(GLenum target, GLuint framebuffer);
void framebuffer_texture_2d(
    GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level);
GLenum check_framebuffer_status(GLenum target);

/**
 * Buffer-object entry points for the asynchronous PBO readback, resolved the same
 * way. buffer_objects_available() reports whether the whole set resolved; when it is
 * false the render path falls back to synchronous glReadPixels.
 */
bool buffer_objects_available();
void gen_buffers(GLsizei n, GLuint *buffers);
void delete_buffers(GLsizei n, const GLuint *buffers);
void bind_buffer(GLenum target, GLuint buffer);
void buffer_data(GLenum target, GLsizeiptr size, const void *data, GLenum usage);
void *map_buffer(GLenum target, GLenum access);
GLboolean unmap_buffer(GLenum target);

} // namespace gl
} // namespace mediampv

#endif // _WIN32

#endif // MEDIAMP_GL_FUNCTIONS_WIN_H
