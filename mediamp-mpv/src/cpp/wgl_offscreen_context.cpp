/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#ifdef _WIN32

#include "wgl_offscreen_context.h"

#include <GL/gl.h> // glGetString, for logging what the driver actually gave us

#include <cstdint>

#include "log.h"

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

constexpr wchar_t kWindowClassName[] = L"mediampv_gl_fallback_producer";

// OpenGL 3.3 core, matching the GLX producer context. mpv's GL renderer works with both
// profiles; the legacy-context fallback below covers drivers without the extension.
constexpr int kContextAttributes[] = {
    WGL_CONTEXT_MAJOR_VERSION_ARB, 3,
    WGL_CONTEXT_MINOR_VERSION_ARB, 3,
    WGL_CONTEXT_PROFILE_MASK_ARB, WGL_CONTEXT_CORE_PROFILE_BIT_ARB,
    0,
};

LRESULT CALLBACK producer_window_proc(HWND window, UINT message, WPARAM w_param, LPARAM l_param) {
    // WM_CLOSE -> DefWindowProc -> DestroyWindow; ending the message loop on
    // WM_DESTROY lets destroy() shut the window thread down with one PostMessage.
    if (message == WM_DESTROY) {
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(window, message, w_param, l_param);
}

bool ensure_window_class(std::string *error) {
    static bool registered = false;
    static std::once_flag once;
    std::call_once(once, [] {
        WNDCLASSEXW window_class{};
        window_class.cbSize = sizeof(window_class);
        // CS_OWNDC: the window keeps one private device context for its whole life,
        // which is what a long-lived GL drawable needs (a cached DC could be recycled
        // by GDI between wglMakeCurrent calls).
        window_class.style = CS_OWNDC;
        window_class.lpfnWndProc = producer_window_proc;
        window_class.hInstance = GetModuleHandleW(nullptr);
        window_class.lpszClassName = kWindowClassName;
        registered = RegisterClassExW(&window_class) != 0 ||
            GetLastError() == ERROR_CLASS_ALREADY_EXISTS;
    });
    if (!registered && error) *error = "RegisterClassExW for the GL fallback window failed";
    return registered;
}

/**
 * Whether [format] on [device_context] belongs to the hardware ICD rather than the
 * Microsoft generic implementation. The generic implementation is OpenGL 1.1 software:
 * wglCreateContext on it succeeds but resolves no post-1.1 entry points, which mpv
 * needs. PFD_GENERIC_FORMAT alone is the software rasterizer; together with
 * PFD_GENERIC_ACCELERATED it is a (long obsolete) MCD driver — neither qualifies.
 */
bool is_accelerated_pixel_format(HDC device_context, int format) {
    PIXELFORMATDESCRIPTOR descriptor{};
    descriptor.nSize = sizeof(descriptor);
    descriptor.nVersion = 1;
    if (format <= 0 ||
        DescribePixelFormat(device_context, format, sizeof(descriptor), &descriptor) == 0) {
        return false;
    }
    return (descriptor.dwFlags & PFD_GENERIC_FORMAT) == 0 &&
        (descriptor.dwFlags & PFD_SUPPORT_OPENGL) != 0;
}

/** First hardware-accelerated RGBA window format offered for [device_context], or 0. */
int scan_for_accelerated_pixel_format(HDC device_context) {
    PIXELFORMATDESCRIPTOR descriptor{};
    descriptor.nSize = sizeof(descriptor);
    descriptor.nVersion = 1;
    const int format_count = DescribePixelFormat(device_context, 1, sizeof(descriptor), &descriptor);
    for (int format = 1; format <= format_count; ++format) {
        if (DescribePixelFormat(device_context, format, sizeof(descriptor), &descriptor) == 0)
            continue;
        const DWORD required = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL;
        if ((descriptor.dwFlags & required) != required) continue;
        if (descriptor.dwFlags & PFD_GENERIC_FORMAT) continue;
        if (descriptor.iPixelType != PFD_TYPE_RGBA) continue;
        if (descriptor.cColorBits < 24) continue;
        return format;
    }
    return 0;
}

bool set_producer_pixel_format(HDC device_context, std::string *error) {
    PIXELFORMATDESCRIPTOR descriptor{};
    descriptor.nSize = sizeof(descriptor);
    descriptor.nVersion = 1;
    descriptor.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL;
    descriptor.iPixelType = PFD_TYPE_RGBA;
    descriptor.cColorBits = 24;
    descriptor.cAlphaBits = 8;
    descriptor.iLayerType = PFD_MAIN_PLANE;

    int format = 0;
    const char *source = "ChoosePixelFormat";
    const int chosen = ChoosePixelFormat(device_context, &descriptor);
    if (is_accelerated_pixel_format(device_context, chosen)) {
        format = chosen;
    } else {
        if (chosen != 0) {
            LOGW("ChoosePixelFormat returned the non-accelerated format %d; scanning instead", chosen);
        }
        format = scan_for_accelerated_pixel_format(device_context);
        source = "scan";
    }
    if (format == 0) {
        if (error) {
            *error = "no hardware-accelerated OpenGL pixel format is available "
                     "(the Microsoft generic implementation is GL 1.1, which mpv cannot use)";
        }
        return false;
    }
    if (!SetPixelFormat(device_context, format, &descriptor)) {
        if (error) *error = "SetPixelFormat for the GL fallback drawable failed";
        LOGE("SetPixelFormat(%d) failed: GetLastError=%lu", format, GetLastError());
        return false;
    }
    LOGI("WGL fallback drawable uses pixel format %d via %s", format, source);
    return true;
}

/**
 * Creates the context and leaves it current. A legacy context comes first: it is also
 * the bootstrap that makes wglGetProcAddress work at all (WGL entry points resolve only
 * while some context is current). If WGL_ARB_create_context is available, a 3.3 core
 * context replaces it; otherwise the legacy context is kept — on an accelerated format
 * it is the driver's full compatibility context, which mpv accepts too.
 */
HGLRC create_and_bind_context(HDC device_context, std::string *error) {
    HGLRC legacy = wglCreateContext(device_context);
    if (!legacy) {
        if (error) *error = "wglCreateContext for the GL fallback context failed";
        LOGE("wglCreateContext failed: GetLastError=%lu", GetLastError());
        return nullptr;
    }
    if (!wglMakeCurrent(device_context, legacy)) {
        if (error) *error = "wglMakeCurrent for the GL fallback context failed";
        LOGE("wglMakeCurrent failed: GetLastError=%lu", GetLastError());
        wglDeleteContext(legacy);
        return nullptr;
    }

    const auto create_context_attribs = reinterpret_cast<create_context_attribs_fn>(
        wglGetProcAddress("wglCreateContextAttribsARB"));
    if (!create_context_attribs) {
        LOGW("wglCreateContextAttribsARB is unavailable; keeping the legacy context");
        return legacy;
    }
    HGLRC core = create_context_attribs(device_context, nullptr, kContextAttributes);
    if (!core) {
        LOGW("wglCreateContextAttribsARB(3.3 core) failed (GetLastError=%lu); "
             "keeping the legacy context", GetLastError());
        return legacy;
    }
    if (!wglMakeCurrent(device_context, core)) {
        LOGW("wglMakeCurrent for the 3.3 core context failed (GetLastError=%lu); "
             "keeping the legacy context", GetLastError());
        wglDeleteContext(core);
        if (!wglMakeCurrent(device_context, legacy)) {
            if (error) *error = "wglMakeCurrent could not restore the legacy GL fallback context";
            wglDeleteContext(legacy);
            return nullptr;
        }
        return legacy;
    }
    wglDeleteContext(legacy);
    return core;
}

} // namespace

namespace mediampv {

// Runs on the dedicated window thread: creates the hidden window, sets its pixel
// format, publishes the handles, then pumps messages until WM_DESTROY. GetMessageW
// keeps the thread inside message retrieval at all times, so cross-thread sent
// messages (activation/focus bookkeeping when other process windows close) are always
// delivered promptly instead of deadlocking their sender.
void wgl_offscreen_context::window_thread_loop() {
    std::string error;
    HWND window = nullptr;
    HDC device_context = nullptr;
    if (ensure_window_class(&error)) {
        window = CreateWindowExW(
            0, kWindowClassName, L"", WS_POPUP, 0, 0, 1, 1,
            nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
        if (!window) {
            error = "CreateWindowExW for the GL fallback drawable failed";
            LOGE("CreateWindowExW failed: GetLastError=%lu", GetLastError());
        }
    }
    if (window) {
        // CS_OWNDC hands out the window's private DC; it stays valid until the window
        // is destroyed and must not be released separately.
        device_context = GetDC(window);
        if (!device_context) {
            error = "GetDC for the GL fallback drawable failed";
        } else if (!set_producer_pixel_format(device_context, &error)) {
            device_context = nullptr;
        }
        if (!device_context) {
            DestroyWindow(window);
            window = nullptr;
        }
    }
    {
        std::lock_guard<std::mutex> lock(startup_mutex_);
        window_ = window;
        device_context_ = device_context;
        window_error_ = error;
        startup_done_ = true;
    }
    startup_cv_.notify_all();
    if (!window) return;

    // This line doubles as a build marker: its presence in a log confirms the running
    // mediampv.dll has the dedicated window pump thread.
    LOGI("WGL fallback window pump thread running");
    MSG message;
    while (GetMessageW(&message, nullptr, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    LOGI("WGL fallback window pump thread exited");
}

wgl_offscreen_context *wgl_offscreen_context::create(std::string *error) {
    auto *context = new wgl_offscreen_context();
    context->window_thread_ = std::thread([context] { context->window_thread_loop(); });
    {
        std::unique_lock<std::mutex> lock(context->startup_mutex_);
        context->startup_cv_.wait(lock, [context] { return context->startup_done_; });
    }
    if (!context->window_) {
        if (error) *error = context->window_error_;
        if (context->window_thread_.joinable()) context->window_thread_.join();
        delete context;
        return nullptr;
    }

    // The GL context itself belongs to the calling (render) thread. HDCs are not
    // thread-affine, so using the window's DC from here is fine.
    context->context_ = create_and_bind_context(context->device_context_, error);
    if (!context->context_) {
        context->shutdown_window_thread();
        delete context;
        return nullptr;
    }

    LOGI("WGL fallback context ready: %s / %s / GL %s",
         reinterpret_cast<const char *>(glGetString(GL_VENDOR)),
         reinterpret_cast<const char *>(glGetString(GL_RENDERER)),
         reinterpret_cast<const char *>(glGetString(GL_VERSION)));
    return context;
}

wgl_offscreen_context::~wgl_offscreen_context() {
    destroy();
}

void wgl_offscreen_context::shutdown_window_thread() {
    if (window_) {
        // WM_CLOSE -> DefWindowProc -> DestroyWindow (on the window thread, as
        // required) -> WM_DESTROY -> PostQuitMessage ends the pump loop.
        PostMessageW(window_, WM_CLOSE, 0, 0);
    }
    if (window_thread_.joinable()) window_thread_.join();
    window_ = nullptr;
    device_context_ = nullptr;
}

void wgl_offscreen_context::destroy() {
    if (!context_ && !window_) {
        if (window_thread_.joinable()) window_thread_.join();
        return;
    }
    if (context_) {
        if (wglGetCurrentContext() == context_) {
            wglMakeCurrent(nullptr, nullptr);
        }
        wglDeleteContext(context_);
        context_ = nullptr;
    }
    shutdown_window_thread();
}

void *wgl_offscreen_context::get_proc_address(const char *name) const {
    if (!name || !*name) return nullptr;
    if (PROC proc = wglGetProcAddress(name)) {
        // Some drivers report "unsupported" as one of these sentinel values instead of
        // null, so a plain null check would hand mpv an unusable pointer.
        const auto value = reinterpret_cast<intptr_t>(proc);
        if (value != 1 && value != 2 && value != 3 && value != -1) {
            return reinterpret_cast<void *>(proc);
        }
    }
    static HMODULE const gl_module = LoadLibraryW(L"opengl32.dll");
    return gl_module ? reinterpret_cast<void *>(GetProcAddress(gl_module, name)) : nullptr;
}

} // namespace mediampv

#endif // _WIN32
