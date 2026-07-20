/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

/**
 * JNI bridge for the WSOLA time-stretch processor (libmediamp_wsola).
 *
 * Kotlin contract: org.openani.mediamp.exoplayer.internal.WsolaProcessorNative.
 *  - Input/output are direct ByteBuffers of interleaved PCM in the sample format fixed at
 *    create() time (SAMPLE_FORMAT_S16 = signed 16-bit, SAMPLE_FORMAT_FLOAT = 32-bit float).
 *  - One frame contains one sample for every channel.
 *  - finishInput signals EOS; drainOutput must keep returning the tail until 0.
 *  - flush discards all buffered state but keeps the configured speed.
 *  - All calls are single-threaded; no locking needed.
 */

#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <exception>
#include <limits>
#include <new>
#include <vector>

#include "scaletempo2.h"

namespace {

constexpr float kInt16ToFloat = 1.0f / 32768.0f;

// Mirrors WsolaProcessorNative.SAMPLE_FORMAT_S16 / SAMPLE_FORMAT_FLOAT.
constexpr jint kSampleFormatS16 = 0;
constexpr jint kSampleFormatFloat = 1;

struct WsolaContext {
    wsola::mp_scaletempo2 wsola;
    int channels = 0;
    int bytes_per_sample = 2;
    bool is_float = false;
    double speed = 1.0;

    // Queued, not yet consumed planar float input (queueInput accepts whatever the
    // caller offers; the WSOLA core only pulls what it currently needs).
    std::vector<std::vector<float>> pending;
    int pending_frames = 0;

    bool finish_signaled = false;
    bool final_set = false; // set_final applied once pending is exhausted

    // Scratch for fill_buffer output, grown on demand (steady-state: no allocation).
    std::vector<std::vector<float>> dest;
    std::vector<float *> dest_ptrs;
    int dest_capacity = 0;
};

WsolaContext *fromHandle(jlong handle)
{
    return reinterpret_cast<WsolaContext *>(handle);
}

void throwIllegalArgument(JNIEnv *env, const char *message)
{
    jclass clazz = env->FindClass("java/lang/IllegalArgumentException");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message);
    }
}

void throwIllegalState(JNIEnv *env, const char *message)
{
    jclass clazz = env->FindClass("java/lang/IllegalStateException");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message);
    }
}

void throwOutOfMemory(JNIEnv *env, const char *message)
{
    jclass clazz = env->FindClass("java/lang/OutOfMemoryError");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message);
    }
}

float sanitizePcmFloat(float v)
{
    // Keep NaN/Inf out of the similarity search and the Android float PCM sink.
    return std::isfinite(v) ? std::min(std::max(v, -1.0f), 1.0f) : 0.0f;
}

int16_t floatToInt16(float v)
{
    // Clamp before scaling to avoid UB on out-of-range float->int conversion.
    long sample = lrintf(sanitizePcmFloat(v) * 32767.0f);
    return static_cast<int16_t>(sample);
}

/** Moves frames from |pending| into the WSOLA input buffer (as many as it needs). */
void feedPending(WsolaContext *ctx)
{
    if (ctx->pending_frames == 0) {
        return;
    }
    float *planes[wsola::WSOLA_MAX_CHANNELS];
    for (int i = 0; i < ctx->channels; ++i) {
        planes[i] = ctx->pending[i].data();
    }
    int read = wsola::mp_scaletempo2_fill_input_buffer(
        &ctx->wsola, planes, ctx->pending_frames, ctx->speed);
    if (read <= 0) {
        return;
    }
    ctx->pending_frames -= read;
    for (int i = 0; i < ctx->channels; ++i) {
        auto &ch = ctx->pending[i];
        std::memmove(ch.data(), ch.data() + read,
                     static_cast<size_t>(ctx->pending_frames) * sizeof(float));
    }
}

void ensureDestCapacity(WsolaContext *ctx, int frames)
{
    if (frames <= ctx->dest_capacity) {
        return;
    }
    for (auto &ch : ctx->dest) {
        ch.resize(static_cast<size_t>(frames));
    }
    for (int i = 0; i < ctx->channels; ++i) {
        ctx->dest_ptrs[i] = ctx->dest[i].data();
    }
    ctx->dest_capacity = frames;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_openani_mediamp_exoplayer_internal_WsolaProcessorNative_create(
    JNIEnv *env, jclass /* clazz */, jint sampleRate, jint channels, jint sampleFormat)
{
    if (sampleRate <= 0 || channels < 1 || channels > wsola::WSOLA_MAX_CHANNELS) {
        throwIllegalArgument(env, "sampleRate must be > 0 and channels in 1..2");
        return 0;
    }
    if (sampleFormat != kSampleFormatS16 && sampleFormat != kSampleFormatFloat) {
        throwIllegalArgument(env, "sampleFormat must be SAMPLE_FORMAT_S16 or SAMPLE_FORMAT_FLOAT");
        return 0;
    }
    WsolaContext *ctx = nullptr;
    try {
        ctx = new WsolaContext();
        ctx->channels = channels;
        ctx->is_float = sampleFormat == kSampleFormatFloat;
        ctx->bytes_per_sample = ctx->is_float ? 4 : 2;
        wsola::mp_scaletempo2_init(&ctx->wsola, channels, sampleRate);
        ctx->pending.assign(static_cast<size_t>(channels), std::vector<float>());
        ctx->dest.assign(static_cast<size_t>(channels), std::vector<float>());
        ctx->dest_ptrs.assign(static_cast<size_t>(channels), nullptr);
    } catch (const std::bad_alloc &) {
        delete ctx;
        throwOutOfMemory(env, "Unable to allocate native WSOLA processor");
        return 0;
    } catch (const std::exception &e) {
        delete ctx;
        throwIllegalState(env, e.what());
        return 0;
    } catch (...) {
        delete ctx;
        throwIllegalState(env, "Native WSOLA initialization failed");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_org_openani_mediamp_exoplayer_internal_WsolaProcessorNative_setSpeed(
    JNIEnv *env, jclass /* clazz */, jlong handle, jfloat speed)
{
    WsolaContext *ctx = fromHandle(handle);
    if (ctx == nullptr) {
        return;
    }
    if (!(speed > 0.0f) || !std::isfinite(speed)) {
        throwIllegalArgument(env, "speed must be finite and positive");
        return;
    }
    ctx->speed = speed;
}

extern "C" JNIEXPORT void JNICALL
Java_org_openani_mediamp_exoplayer_internal_WsolaProcessorNative_queueInput(
    JNIEnv *env, jclass /* clazz */, jlong handle, jobject buf, jint byteOffset,
    jint frames)
{
    WsolaContext *ctx = fromHandle(handle);
    if (ctx == nullptr) {
        return;
    }
    if (frames < 0) {
        throwIllegalArgument(env, "queueInput frames must be non-negative");
        return;
    }
    if (frames == 0) {
        return;
    }
    if (ctx->finish_signaled) {
        throwIllegalState(env, "queueInput called after finishInput");
        return;
    }
    const auto *base = static_cast<const uint8_t *>(env->GetDirectBufferAddress(buf));
    if (base == nullptr) {
        throwIllegalArgument(env, "queueInput requires a direct ByteBuffer");
        return;
    }
    const jlong capacity = env->GetDirectBufferCapacity(buf);
    const jlong needed = static_cast<jlong>(frames) * ctx->channels *
                         static_cast<jlong>(ctx->bytes_per_sample);
    if (byteOffset < 0 || byteOffset % static_cast<jint>(ctx->bytes_per_sample) != 0) {
        throwIllegalArgument(env, "queueInput byteOffset must be non-negative and sample aligned");
        return;
    }
    if (capacity < 0 || static_cast<jlong>(byteOffset) > capacity ||
        needed > capacity - static_cast<jlong>(byteOffset)) {
        throwIllegalArgument(env, "queueInput range exceeds the direct ByteBuffer capacity");
        return;
    }
    const auto address = reinterpret_cast<std::uintptr_t>(base + byteOffset);
    if (address % static_cast<std::uintptr_t>(ctx->bytes_per_sample) != 0) {
        throwIllegalArgument(env, "queueInput direct buffer address is not sample aligned");
        return;
    }
    if (frames > std::numeric_limits<int>::max() - ctx->pending_frames) {
        throwIllegalArgument(env, "queueInput exceeds the native pending-frame limit");
        return;
    }

    const int total = ctx->pending_frames + frames;
    try {
        for (int ch = 0; ch < ctx->channels; ++ch) {
            auto &plane = ctx->pending[ch];
            if (static_cast<int>(plane.size()) < total) {
                plane.resize(static_cast<size_t>(total));
            }
            float *out = plane.data() + ctx->pending_frames;
            if (ctx->is_float) {
                const auto *in = reinterpret_cast<const float *>(base + byteOffset) + ch;
                for (int i = 0; i < frames; ++i, in += ctx->channels) {
                    out[i] = sanitizePcmFloat(*in);
                }
            } else {
                const auto *in = reinterpret_cast<const int16_t *>(base + byteOffset) + ch;
                for (int i = 0; i < frames; ++i, in += ctx->channels) {
                    out[i] = static_cast<float>(*in) * kInt16ToFloat;
                }
            }
        }
    } catch (const std::bad_alloc &) {
        throwOutOfMemory(env, "Unable to grow native WSOLA input buffer");
        return;
    } catch (const std::exception &e) {
        throwIllegalState(env, e.what());
        return;
    } catch (...) {
        throwIllegalState(env, "Native WSOLA input buffering failed");
        return;
    }
    ctx->pending_frames = total;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_openani_mediamp_exoplayer_internal_WsolaProcessorNative_drainOutput(
    JNIEnv *env, jclass /* clazz */, jlong handle, jobject buf, jint maxFrames)
{
    WsolaContext *ctx = fromHandle(handle);
    if (ctx == nullptr) {
        return 0;
    }
    if (maxFrames < 0) {
        throwIllegalArgument(env, "drainOutput maxFrames must be non-negative");
        return 0;
    }
    if (maxFrames == 0) {
        return 0;
    }
    auto *dstBase = static_cast<uint8_t *>(env->GetDirectBufferAddress(buf));
    if (dstBase == nullptr) {
        throwIllegalArgument(env, "drainOutput requires a direct ByteBuffer");
        return 0;
    }
    const jlong capacity = env->GetDirectBufferCapacity(buf);
    const jlong needed = static_cast<jlong>(maxFrames) * ctx->channels *
                         static_cast<jlong>(ctx->bytes_per_sample);
    if (capacity < 0 || capacity < needed) {
        throwIllegalArgument(env, "drainOutput buffer smaller than maxFrames * channels * bytesPerSample");
        return 0;
    }
    if (reinterpret_cast<std::uintptr_t>(dstBase) %
        static_cast<std::uintptr_t>(ctx->bytes_per_sample) != 0) {
        throwIllegalArgument(env, "drainOutput direct buffer address is not sample aligned");
        return 0;
    }

    try {
        ensureDestCapacity(ctx, maxFrames);

        int produced = 0;
        while (produced < maxFrames) {
            // Feed queued input until the processor can render (or we run out).
            while (!wsola::mp_scaletempo2_frames_available(&ctx->wsola, ctx->speed)) {
                if (ctx->pending_frames > 0) {
                    feedPending(ctx);
                } else if (ctx->finish_signaled) {
                    if (ctx->final_set) {
                        return produced; // EOS tail fully drained
                    }
                    wsola::mp_scaletempo2_set_final(&ctx->wsola);
                    ctx->final_set = true;
                } else {
                    return produced; // waiting for more input
                }
            }
            int rendered = wsola::mp_scaletempo2_fill_buffer(
                &ctx->wsola, ctx->dest_ptrs.data(), maxFrames - produced, ctx->speed);
            if (rendered <= 0) {
                break;
            }
            for (int ch = 0; ch < ctx->channels; ++ch) {
                const float *in = ctx->dest[ch].data();
                if (ctx->is_float) {
                    auto *out = reinterpret_cast<float *>(dstBase) +
                                static_cast<size_t>(produced) * ctx->channels + ch;
                    for (int i = 0; i < rendered; ++i, out += ctx->channels) {
                        *out = sanitizePcmFloat(in[i]);
                    }
                } else {
                    auto *out = reinterpret_cast<int16_t *>(dstBase) +
                                static_cast<size_t>(produced) * ctx->channels + ch;
                    for (int i = 0; i < rendered; ++i, out += ctx->channels) {
                        *out = floatToInt16(in[i]);
                    }
                }
            }
            produced += rendered;
        }
        return produced;
    } catch (const std::bad_alloc &) {
        throwOutOfMemory(env, "Unable to grow native WSOLA processing buffer");
        return 0;
    } catch (const std::exception &e) {
        throwIllegalState(env, e.what());
        return 0;
    } catch (...) {
        throwIllegalState(env, "Native WSOLA processing failed");
        return 0;
    }
}

extern "C" JNIEXPORT jdouble JNICALL
Java_org_openani_mediamp_exoplayer_internal_WsolaProcessorNative_getPendingInputFrames(
    JNIEnv * /* env */, jclass /* clazz */, jlong handle)
{
    WsolaContext *ctx = fromHandle(handle);
    if (ctx == nullptr) {
        return 0.0;
    }
    // pending is queued in the JNI adapter but has not entered scaletempo2 yet; get_latency
    // reports the frames already buffered inside scaletempo2 and not represented in its output.
    const double pending = static_cast<double>(ctx->pending_frames) +
        mp_scaletempo2_get_latency(&ctx->wsola, ctx->speed);
    return std::isfinite(pending) ? std::max(0.0, pending) : 0.0;
}

extern "C" JNIEXPORT void JNICALL
Java_org_openani_mediamp_exoplayer_internal_WsolaProcessorNative_flush(
    JNIEnv * /* env */, jclass /* clazz */, jlong handle)
{
    WsolaContext *ctx = fromHandle(handle);
    if (ctx == nullptr) {
        return;
    }
    wsola::mp_scaletempo2_reset(&ctx->wsola);
    ctx->pending_frames = 0;
    ctx->finish_signaled = false;
    ctx->final_set = false;
    // Speed is intentionally kept (per Kotlin contract).
}

extern "C" JNIEXPORT void JNICALL
Java_org_openani_mediamp_exoplayer_internal_WsolaProcessorNative_finishInput(
    JNIEnv * /* env */, jclass /* clazz */, jlong handle)
{
    WsolaContext *ctx = fromHandle(handle);
    if (ctx == nullptr) {
        return;
    }
    // Actual set_final is applied lazily in drainOutput once queued input is
    // exhausted, so the tail is padded with silence and fully rendered.
    ctx->finish_signaled = true;
}

extern "C" JNIEXPORT void JNICALL
Java_org_openani_mediamp_exoplayer_internal_WsolaProcessorNative_release(
    JNIEnv * /* env */, jclass /* clazz */, jlong handle)
{
    delete fromHandle(handle);
}
