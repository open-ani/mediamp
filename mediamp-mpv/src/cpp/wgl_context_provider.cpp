/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#ifdef _WIN32

#include <windows.h>

#include <cstdint>

#include "log.h"
#include "wgl_context_provider.h"

// WGL_ARB_create_context. Defined locally instead of pulling in <GL/wglext.h>: the
// values are frozen registry constants and the header is not part of the Windows SDK,
// only of the MinGW GL headers.
#ifndef WGL_CONTEXT_MAJOR_VERSION_ARB
#define WGL_CONTEXT_MAJOR_VERSION_ARB 0x2091
#endif
#ifndef WGL_CONTEXT_MINOR_VERSION_ARB
#define WGL_CONTEXT_MINOR_VERSION_ARB 0x2092
#endif
#ifndef WGL_CONTEXT_PROFILE_MASK_ARB
#define WGL_CONTEXT_PROFILE_MASK_ARB 0x9126
#endif
#ifndef WGL_CONTEXT_CORE_PROFILE_BIT_ARB
#define WGL_CONTEXT_CORE_PROFILE_BIT_ARB 0x00000001
#endif

namespace {

using create_context_attribs_fn = HGLRC(WINAPI *)(HDC, HGLRC, const int *);

constexpr wchar_t kWindowClassName[] = L"mediampv_gl_producer";

// OpenGL 3.3 core, matching the GLX producer context. mpv's GL renderer works with both
// profiles; core keeps the producer away from deprecated state that a driver might
// otherwise validate against Skiko's compatibility context.
constexpr int kContextAttributes[] = {
    WGL_CONTEXT_MAJOR_VERSION_ARB, 3,
    WGL_CONTEXT_MINOR_VERSION_ARB, 3,
    WGL_CONTEXT_PROFILE_MASK_ARB, WGL_CONTEXT_CORE_PROFILE_BIT_ARB,
    0,
};

/** Restores whatever context was current on this thread when it goes out of scope. */
struct scoped_current_context final {
    scoped_current_context() : device_context(wglGetCurrentDC()), context(wglGetCurrentContext()) {}

    ~scoped_current_context() {
        // Both null is the normal case on the render thread; wglMakeCurrent(null, null)
        // is the documented way to release, so it is also the right restore.
        wglMakeCurrent(device_context, context);
    }

    scoped_current_context(const scoped_current_context &) = delete;
    scoped_current_context &operator=(const scoped_current_context &) = delete;

    HDC device_context;
    HGLRC context;
};

bool ensure_window_class(std::string *error) {
    static bool registered = false;
    static std::once_flag once;
    std::call_once(
        once, [] {
            WNDCLASSEXW window_class{};
            window_class.cbSize = sizeof(window_class);
            // CS_OWNDC: the window keeps one private device context for its whole life,
            // which is what a long-lived GL drawable needs (a cached DC could be
            // recycled by GDI between wglMakeCurrent calls).
            window_class.style = CS_OWNDC;
            window_class.lpfnWndProc = DefWindowProcW;
            window_class.hInstance = GetModuleHandleW(nullptr);
            window_class.lpszClassName = kWindowClassName;
            registered = RegisterClassExW(&window_class) != 0 ||
                GetLastError() == ERROR_CLASS_ALREADY_EXISTS;
        });
    if (!registered && error) *error = "RegisterClassExW for the GL producer window failed";
    return registered;
}

PIXELFORMATDESCRIPTOR default_pixel_format_descriptor() {
    PIXELFORMATDESCRIPTOR descriptor{};
    descriptor.nSize = sizeof(descriptor);
    descriptor.nVersion = 1;
    descriptor.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL;
    descriptor.iPixelType = PFD_TYPE_RGBA;
    descriptor.cColorBits = 24;
    descriptor.cAlphaBits = 8;
    descriptor.iLayerType = PFD_MAIN_PLANE;
    return descriptor;
}

/**
 * Gives [device_context] the pixel format Skiko's own drawable uses, so context B is
 * created against a format that is share-compatible with context A. Falls back to a
 * plain RGBA format when Skiko's HDC cannot be described (it belongs to another thread's
 * window and may be gone).
 */
bool set_producer_pixel_format(HDC device_context, HDC skiko_device_context, std::string *error) {
    PIXELFORMATDESCRIPTOR descriptor = default_pixel_format_descriptor();
    if (skiko_device_context) {
        const int skiko_format = GetPixelFormat(skiko_device_context);
        PIXELFORMATDESCRIPTOR skiko_descriptor{};
        skiko_descriptor.nSize = sizeof(skiko_descriptor);
        skiko_descriptor.nVersion = 1;
        if (skiko_format > 0 &&
            DescribePixelFormat(
                skiko_device_context, skiko_format, sizeof(skiko_descriptor), &skiko_descriptor) != 0) {
            // Keep Skiko's format as-is (same adapter, so it is offered for our window
            // too); matching it maximizes the chance the driver accepts the share group.
            descriptor = skiko_descriptor;
            descriptor.dwFlags |= PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL;
        }
    }

    int format = ChoosePixelFormat(device_context, &descriptor);
    if (format == 0) {
        descriptor = default_pixel_format_descriptor();
        format = ChoosePixelFormat(device_context, &descriptor);
    }
    if (format == 0) {
        if (error) *error = "ChoosePixelFormat for the GL producer drawable failed";
        return false;
    }
    if (!SetPixelFormat(device_context, format, &descriptor)) {
        if (error) *error = "SetPixelFormat for the GL producer drawable failed";
        return false;
    }
    return true;
}

/**
 * Resolves `wglCreateContextAttribsARB` through a throwaway legacy context on
 * [device_context]. WGL entry points can only be resolved while a context is current,
 * and the producer thread has none yet — hence the bootstrap. The pointer is tied to the
 * drawable's pixel format, which is the same one context B is created with.
 */
create_context_attribs_fn resolve_create_context_attribs(HDC device_context) {
    HGLRC bootstrap = wglCreateContext(device_context);
    if (!bootstrap) return nullptr;

    create_context_attribs_fn create_context_attribs = nullptr;
    {
        scoped_current_context previous;
        if (wglMakeCurrent(device_context, bootstrap)) {
            create_context_attribs = reinterpret_cast<create_context_attribs_fn>(
                wglGetProcAddress("wglCreateContextAttribsARB"));
        }
        // previous is restored (normally to no context) before the bootstrap context is
        // deleted: wglDeleteContext fails while the context is still current.
    }
    wglDeleteContext(bootstrap);
    return create_context_attribs;
}

} // namespace

namespace mediampv {

wgl_context_provider::wgl_context_provider(
    HWND window, HDC device_context, HGLRC context, uint64_t environment_identity)
    : gl_context_provider(environment_identity),
      window_(window),
      device_context_(device_context),
      context_(context) {}

wgl_context_provider::~wgl_context_provider() {
    destroy();
}

wgl_context_provider *wgl_context_provider::create(
    const gl_render_environment &environment, std::string *error) {
    auto share_context = reinterpret_cast<HGLRC>(static_cast<uintptr_t>(environment.share_context));
    auto skiko_device_context = reinterpret_cast<HDC>(static_cast<uintptr_t>(environment.native_display));
    if (!share_context) {
        if (error) *error = "WGL render environment requires a live share context";
        return nullptr;
    }
    if (environment.identity == 0) {
        if (error) *error = "WGL render environment identity must be non-zero";
        return nullptr;
    }
    if (!ensure_window_class(error)) return nullptr;

    HWND window = CreateWindowExW(
        0, kWindowClassName, L"", WS_POPUP, 0, 0, 1, 1,
        nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
    if (!window) {
        if (error) *error = "CreateWindowExW for the GL producer drawable failed";
        return nullptr;
    }
    // CS_OWNDC hands out the window's private DC; it stays valid until the window is
    // destroyed and must not be released separately.
    HDC device_context = GetDC(window);
    if (!device_context) {
        DestroyWindow(window);
        if (error) *error = "GetDC for the GL producer drawable failed";
        return nullptr;
    }
    if (!set_producer_pixel_format(device_context, skiko_device_context, error)) {
        DestroyWindow(window);
        return nullptr;
    }

    // The supplied context A is solely a share-list source; it stays current and owned by
    // Skiko on its own thread. Sharing across profiles (A is a compatibility context) is
    // explicitly allowed by WGL_ARB_create_context.
    HGLRC context = nullptr;
    if (auto create_context_attribs = resolve_create_context_attribs(device_context)) {
        context = create_context_attribs(device_context, share_context, kContextAttributes);
    }
    if (!context) {
        // WGL_ARB_create_context is unavailable or refused 3.3 core. A legacy context is
        // whatever version the driver defaults to (a compatibility profile), which mpv's
        // GL renderer also accepts; wglShareLists then joins it to A's share group.
        context = wglCreateContext(device_context);
        if (context && !wglShareLists(share_context, context)) {
            wglDeleteContext(context);
            context = nullptr;
        }
    }
    if (!context) {
        DestroyWindow(window);
        if (error) {
            *error = "creating a shared WGL producer context failed; the Skiko OpenGL "
                     "environment may already be stale";
        }
        return nullptr;
    }

    auto *provider = new wgl_context_provider(window, device_context, context, environment.identity);
    LOGI("created shared WGL producer context=%p share=%p environment=%llu",
         static_cast<void *>(context), static_cast<void *>(share_context),
         static_cast<unsigned long long>(environment.identity));
    return provider;
}

bool wgl_context_provider::make_current() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!context_ || !device_context_) return fail_locked("WGL producer context is destroyed");

    const auto current_thread = std::this_thread::get_id();
    if (owner_bound_ && owner_thread_ != current_thread) {
        return fail_locked("WGL producer context cannot move to another thread");
    }
    if (!wglMakeCurrent(device_context_, context_)) {
        return fail_locked("wglMakeCurrent for producer context failed");
    }

    owner_thread_ = current_thread;
    owner_bound_ = true;
    current_on_owner_ = true;
    return true;
}

bool wgl_context_provider::clear_current() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!context_) return true;
    if (!owner_bound_ || owner_thread_ != std::this_thread::get_id()) {
        return fail_locked("only the WGL producer owner thread may clear its context");
    }
    if (!wglMakeCurrent(nullptr, nullptr)) return fail_locked("wglMakeCurrent clear failed");
    current_on_owner_ = false;
    return true;
}

bool wgl_context_provider::destroy() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!context_ && !window_) return true;
    if (current_on_owner_) {
        if (owner_thread_ != std::this_thread::get_id() || wglGetCurrentContext() != context_) {
            return fail_locked(
                "WGL producer context is still current; clear it on the render thread before teardown");
        }
        if (!wglMakeCurrent(nullptr, nullptr)) {
            return fail_locked("wglMakeCurrent clear during teardown failed");
        }
        current_on_owner_ = false;
    }

    // wglDeleteContext only succeeds once the context is current nowhere, and
    // DestroyWindow only works on the creating thread — which is the same render thread
    // that owned the context, because the provider is created and destroyed there.
    if (context_) wglDeleteContext(context_);
    if (window_) DestroyWindow(window_);
    context_ = nullptr;
    device_context_ = nullptr;
    window_ = nullptr;
    return true;
}

void *wgl_context_provider::get_proc_address(const char *name) const {
    if (!name || !*name) return nullptr;
    if (PROC proc = wglGetProcAddress(name)) {
        // Some drivers report "unsupported" as one of these sentinel values instead of
        // null, so a plain null check would hand mpv an unusable pointer.
        const auto value = reinterpret_cast<intptr_t>(proc);
        if (value != 1 && value != 2 && value != 3 && value != -1) {
            return reinterpret_cast<void *>(proc);
        }
    }
    // opengl32.dll exports the OpenGL 1.1 core, which wglGetProcAddress deliberately
    // does not resolve.
    static HMODULE const gl_module = LoadLibraryW(L"opengl32.dll");
    return gl_module ? reinterpret_cast<void *>(GetProcAddress(gl_module, name)) : nullptr;
}

std::string wgl_context_provider::last_error() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return last_error_;
}

bool wgl_context_provider::fail_locked(const char *message) {
    set_error_locked(message);
    LOGE("%s (GetLastError=%lu)", message, GetLastError());
    return false;
}

void wgl_context_provider::set_error_locked(const char *message) {
    last_error_ = message ? message : "unknown WGL provider error";
}

} // namespace mediampv

#endif // _WIN32
