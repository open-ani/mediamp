/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#include "png_writer.h"

#if defined(_WIN32) || (defined(__linux__) && !defined(__ANDROID__))

#include <cstddef>

#include "log.h"

#if defined(_WIN32)

#include <initguid.h>
#include <windows.h>
#include <wincodec.h>

#include <vector>

namespace {

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

template <typename T>
void safe_release(T *&object) {
    if (object) {
        object->Release();
        object = nullptr;
    }
}

} // namespace

namespace mediampv {

bool write_argb_png(const char *path, int width, int height, const uint32_t *pixels) {
    if (!path || !pixels || width <= 0 || height <= 0) return false;

    const size_t pixel_count = static_cast<size_t>(width) * static_cast<size_t>(height);
    std::vector<uint32_t> opaque(pixels, pixels + pixel_count);
    for (uint32_t &pixel : opaque) pixel |= 0xFF000000u;

    scoped_co_init com;
    if (!com.usable) {
        LOGE("CoInitializeEx failed; cannot encode %s", path);
        return false;
    }
    bool ok = false;
    IWICImagingFactory *factory = nullptr;
    IWICStream *stream = nullptr;
    IWICBitmapEncoder *encoder = nullptr;
    IWICBitmapFrameEncode *frame = nullptr;
    do {
        if (FAILED(CoCreateInstance(
                CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
                __uuidof(IWICImagingFactory), reinterpret_cast<void **>(&factory)))) break;
        if (FAILED(factory->CreateStream(&stream))) break;
        int wide_length = MultiByteToWideChar(CP_UTF8, 0, path, -1, nullptr, 0);
        std::vector<wchar_t> wide_path(static_cast<size_t>(wide_length > 0 ? wide_length : 1));
        MultiByteToWideChar(CP_UTF8, 0, path, -1, wide_path.data(), wide_length);
        if (FAILED(stream->InitializeFromFilename(wide_path.data(), GENERIC_WRITE))) break;
        if (FAILED(factory->CreateEncoder(GUID_ContainerFormatPng, nullptr, &encoder))) break;
        if (FAILED(encoder->Initialize(stream, WICBitmapEncoderNoCache))) break;
        if (FAILED(encoder->CreateNewFrame(&frame, nullptr))) break;
        if (FAILED(frame->Initialize(nullptr))) break;
        if (FAILED(frame->SetSize(static_cast<UINT>(width), static_cast<UINT>(height)))) break;
        // ARGB ints are BGRA bytes in little-endian memory.
        WICPixelFormatGUID format = GUID_WICPixelFormat32bppBGRA;
        if (FAILED(frame->SetPixelFormat(&format))) break;
        if (FAILED(frame->WritePixels(
                static_cast<UINT>(height), static_cast<UINT>(width) * 4,
                static_cast<UINT>(opaque.size() * 4),
                reinterpret_cast<BYTE *>(opaque.data())))) break;
        if (FAILED(frame->Commit())) break;
        if (FAILED(encoder->Commit())) break;
        ok = true;
    } while (false);
    safe_release(frame);
    safe_release(encoder);
    safe_release(stream);
    safe_release(factory);
    if (!ok) LOGE("PNG encoding failed for %s", path);
    return ok;
}

} // namespace mediampv

#else // zlib encoder

#include <zlib.h>

#include <array>
#include <cstdio>
#include <cstring>
#include <vector>

namespace {

void append_u32_be(std::vector<uint8_t> &out, uint32_t value) {
    out.push_back(static_cast<uint8_t>(value >> 24));
    out.push_back(static_cast<uint8_t>(value >> 16));
    out.push_back(static_cast<uint8_t>(value >> 8));
    out.push_back(static_cast<uint8_t>(value));
}

void append_png_chunk(
    std::vector<uint8_t> &out, const char type[4], const uint8_t *data, size_t size) {
    append_u32_be(out, static_cast<uint32_t>(size));
    const size_t type_offset = out.size();
    out.insert(out.end(), type, type + 4);
    if (size != 0) out.insert(out.end(), data, data + size);
    const auto checksum = crc32(
        0L, reinterpret_cast<const Bytef *>(out.data() + type_offset),
        static_cast<uInt>(4 + size));
    append_u32_be(out, static_cast<uint32_t>(checksum));
}

} // namespace

namespace mediampv {

bool write_argb_png(const char *path, int width, int height, const uint32_t *pixels) {
    if (!path || !pixels || width <= 0 || height <= 0) return false;

    // One filter byte (None) plus RGBA per scanline, in PNG's top-down order.
    const size_t stride = static_cast<size_t>(width) * 4;
    std::vector<uint8_t> scanlines(static_cast<size_t>(height) * (stride + 1));
    for (int y = 0; y < height; ++y) {
        uint8_t *row = scanlines.data() + static_cast<size_t>(y) * (stride + 1);
        row[0] = 0; // PNG filter None
        const uint32_t *source = pixels + static_cast<size_t>(y) * static_cast<size_t>(width);
        for (int x = 0; x < width; ++x) {
            const uint32_t pixel = source[x];
            uint8_t *target = row + 1 + static_cast<size_t>(x) * 4;
            target[0] = static_cast<uint8_t>((pixel >> 16) & 0xFF);
            target[1] = static_cast<uint8_t>((pixel >> 8) & 0xFF);
            target[2] = static_cast<uint8_t>(pixel & 0xFF);
            target[3] = 0xFF;
        }
    }

    uLongf compressed_size = compressBound(static_cast<uLong>(scanlines.size()));
    std::vector<uint8_t> compressed(compressed_size);
    if (compress2(
            compressed.data(), &compressed_size, scanlines.data(),
            static_cast<uLong>(scanlines.size()), Z_BEST_SPEED) != Z_OK) {
        LOGE("PNG deflate failed for %s", path);
        return false;
    }
    compressed.resize(compressed_size);

    std::vector<uint8_t> png;
    constexpr std::array<uint8_t, 8> signature = {137, 80, 78, 71, 13, 10, 26, 10};
    png.insert(png.end(), signature.begin(), signature.end());
    std::array<uint8_t, 13> ihdr{};
    ihdr[0] = static_cast<uint8_t>(width >> 24);
    ihdr[1] = static_cast<uint8_t>(width >> 16);
    ihdr[2] = static_cast<uint8_t>(width >> 8);
    ihdr[3] = static_cast<uint8_t>(width);
    ihdr[4] = static_cast<uint8_t>(height >> 24);
    ihdr[5] = static_cast<uint8_t>(height >> 16);
    ihdr[6] = static_cast<uint8_t>(height >> 8);
    ihdr[7] = static_cast<uint8_t>(height);
    ihdr[8] = 8; // bits/component
    ihdr[9] = 6; // RGBA
    append_png_chunk(png, "IHDR", ihdr.data(), ihdr.size());
    append_png_chunk(png, "IDAT", compressed.data(), compressed.size());
    append_png_chunk(png, "IEND", nullptr, 0);

    FILE *file = std::fopen(path, "wb");
    if (!file) {
        LOGE("cannot open %s for writing", path);
        return false;
    }
    const bool ok = std::fwrite(png.data(), 1, png.size(), file) == png.size();
    std::fclose(file);
    if (!ok) LOGE("writing %s failed", path);
    return ok;
}

} // namespace mediampv

#endif // _WIN32

#endif // _WIN32 || desktop Linux
