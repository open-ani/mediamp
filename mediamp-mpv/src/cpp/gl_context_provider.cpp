/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#include "gl_context_provider.h"

#ifdef MEDIAMP_OPENGL_RENDER

#if defined(_WIN32)
#include "wgl_context_provider.h"
#else
#include "glx_context_provider.h"
#endif

namespace mediampv {

gl_context_provider *create_gl_context_provider(
    const gl_render_environment &environment, std::string *error) {
#if defined(_WIN32)
    return wgl_context_provider::create(environment, error);
#else
    return glx_context_provider::create(environment, error);
#endif
}

} // namespace mediampv

#endif // MEDIAMP_OPENGL_RENDER
