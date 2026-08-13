/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#ifdef _WIN32

#include "png_writer_win.h"

#include <windows.h>
#include <wincodec.h>

#include "log.h"

namespace {

template<typename T>
void safe_release(T *&object) {
    if (object) {
        object->Release();
        object = nullptr;
    }
}

struct scoped_co_init final {
    scoped_co_init() {
        HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        // RPC_E_CHANGED_MODE: already initialized as STA on this thread; usable as-is.
        initialized = SUCCEEDED(hr);
        usable = initialized || hr == RPC_E_CHANGED_MODE;
    }
    ~scoped_co_init() {
        if (initialized) CoUninitialize();
    }
    bool initialized = false;
    bool usable = false;
};

} // namespace

namespace mediampv {

bool write_argb_png_wic(const char *path, int width, int height,
                        const std::vector<uint32_t> &pixels) {
    if (!path || width <= 0 || height <= 0 ||
        pixels.size() < static_cast<size_t>(width) * height) {
        return false;
    }
    scoped_co_init com;
    if (!com.usable) {
        LOGE("CoInitializeEx failed");
        return false;
    }
    bool ok = false;
    IWICImagingFactory *factory = nullptr;
    IWICStream *stream = nullptr;
    IWICBitmapEncoder *encoder = nullptr;
    IWICBitmapFrameEncode *frame = nullptr;
    do {
        if (FAILED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
                                    __uuidof(IWICImagingFactory),
                                    reinterpret_cast<void **>(&factory)))) break;
        if (FAILED(factory->CreateStream(&stream))) break;
        int wide_length = MultiByteToWideChar(CP_UTF8, 0, path, -1, nullptr, 0);
        std::vector<wchar_t> wide_path((size_t) (wide_length > 0 ? wide_length : 1));
        MultiByteToWideChar(CP_UTF8, 0, path, -1, wide_path.data(), wide_length);
        if (FAILED(stream->InitializeFromFilename(wide_path.data(), GENERIC_WRITE))) break;
        if (FAILED(factory->CreateEncoder(GUID_ContainerFormatPng, nullptr, &encoder))) break;
        if (FAILED(encoder->Initialize(stream, WICBitmapEncoderNoCache))) break;
        if (FAILED(encoder->CreateNewFrame(&frame, nullptr))) break;
        if (FAILED(frame->Initialize(nullptr))) break;
        if (FAILED(frame->SetSize((UINT) width, (UINT) height))) break;
        // ARGB ints are BGRA bytes in little-endian memory.
        WICPixelFormatGUID format = GUID_WICPixelFormat32bppBGRA;
        if (FAILED(frame->SetPixelFormat(&format))) break;
        if (FAILED(frame->WritePixels(
                (UINT) height, (UINT) width * 4, (UINT) (pixels.size() * 4),
                reinterpret_cast<BYTE *>(const_cast<uint32_t *>(pixels.data()))))) break;
        if (FAILED(frame->Commit())) break;
        if (FAILED(encoder->Commit())) break;
        ok = true;
    } while (false);
    safe_release(frame);
    safe_release(encoder);
    safe_release(stream);
    safe_release(factory);
    return ok;
}

} // namespace mediampv

#endif // _WIN32
