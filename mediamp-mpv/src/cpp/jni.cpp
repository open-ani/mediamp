#include <iostream>
#include <cstdint>
#include <limits>
#include <vector>
#include <jni.h>
#include <cstdio>
#include "mpv_handle_t.h"
#include "method_cache.h"

#define FN(name) Java_org_openani_mediamp_mpv_MPVHandleKt_##name
#define FN_ANDROID(name) Java_org_openani_mediamp_mpv_MPVHandleAndroid_##name
#define FN_DESKTOP(name) Java_org_openani_mediamp_mpv_MPVHandleDesktop_##name

namespace {

mediampv::mpv_handle_t *get_instance(jlong ptr) {
    return reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
}

#if defined(_WIN32) || defined(__APPLE__)
// Shared body of nReadSurfacePixels{D3D11,Macos}: returns the latest frame as an ARGB
// jintArray and writes [width, height] into dims, or null when no frame is available.
jintArray read_surface_pixels_to_java(JNIEnv *env, jlong ptr, jintArray dims) {
    auto *instance = get_instance(ptr);
    if (!instance || !dims || env->GetArrayLength(dims) < 2) {
        return nullptr;
    }
    std::vector<uint32_t> pixels;
    int width = 0, height = 0;
    if (!instance->read_surface_pixels(pixels, width, height) || pixels.empty()) {
        return nullptr;
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(pixels.size()));
    if (!result) {
        return nullptr; // OOM; exception pending
    }
    env->SetIntArrayRegion(
        result, 0, static_cast<jsize>(pixels.size()),
        reinterpret_cast<const jint *>(pixels.data()));
    const jint dims_out[2] = {width, height};
    env->SetIntArrayRegion(dims, 0, 2, dims_out);
    return result;
}
#endif

struct scoped_utf_chars final {
    scoped_utf_chars(JNIEnv *env, jstring string)
            : env(env), string(string), chars(string ? env->GetStringUTFChars(string, nullptr) : nullptr) {}

    ~scoped_utf_chars() {
        if (env && string && chars) {
            env->ReleaseStringUTFChars(string, chars);
        }
    }

    scoped_utf_chars(const scoped_utf_chars &) = delete;
    scoped_utf_chars &operator=(const scoped_utf_chars &) = delete;

    bool valid() const {
        return string && chars;
    }

    const char *get() const {
        return chars;
    }

private:
    JNIEnv *env;
    jstring string;
    const char *chars;
};

} // namespace

extern "C" {
    JNIEXPORT jboolean JNICALL FN(nGlobalInit)(JNIEnv *env, jclass clazz);
    JNIEXPORT jlong JNICALL FN(nMake)(JNIEnv *env, jclass clazz, jobject app_context);
    JNIEXPORT jboolean JNICALL FN(nInitialize)(JNIEnv *env, jclass clazz, jlong ptr);
    JNIEXPORT jboolean JNICALL FN(nSetEventListener)(JNIEnv *env, jclass clazz, jlong ptr, jobject listener);
    JNIEXPORT jboolean JNICALL FN(nSetRenderUpdateListener)(JNIEnv *env, jclass clazz, jlong ptr, jobject listener);

    /**
     * 执行 mpv 命令
     */
    JNIEXPORT jboolean JNICALL FN(nCommand)(JNIEnv *env, jclass clazz, jlong ptr, jobjectArray args);
    /**
     * 设置 mpv 选项
     */
    JNIEXPORT jboolean JNICALL FN(nOption)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jstring value);

    // 设置播放器属性
    JNIEXPORT jint JNICALL FN(nGetPropertyInt)(JNIEnv *env, jclass clazz, jlong ptr, jstring key);
    JNIEXPORT jdouble JNICALL FN(nGetPropertyDouble)(JNIEnv *env, jclass clazz, jlong ptr, jstring key);
    JNIEXPORT jboolean JNICALL FN(nGetPropertyBoolean)(JNIEnv *env, jclass clazz, jlong ptr, jstring key);
    JNIEXPORT jstring JNICALL FN(nGetPropertyString)(JNIEnv *env, jclass clazz, jlong ptr, jstring key);

    JNIEXPORT jboolean JNICALL FN(nSetPropertyString)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jstring value);
    JNIEXPORT jboolean JNICALL FN(nSetPropertyInt)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jint value);
    JNIEXPORT jboolean JNICALL FN(nSetPropertyDouble)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jdouble value);
    JNIEXPORT jboolean JNICALL FN(nSetPropertyBoolean)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jboolean value);

    JNIEXPORT jboolean JNICALL FN(nObserveProperty)(JNIEnv *env, jclass clazz, jlong ptr, jstring name, jint format, jlong reply_data);
    JNIEXPORT jboolean JNICALL FN(nUnobserveProperty)(JNIEnv *env, jclass clazz, jlong ptr, jlong reply_data);
    JNIEXPORT jboolean JNICALL FN(nRegisterSeekableInput)(JNIEnv *env, jclass clazz, jlong ptr, jobject input, jstring uri, jlong size);
    JNIEXPORT jboolean JNICALL FN(nUnregisterSeekableInput)(JNIEnv *env, jclass clazz, jlong ptr, jstring uri);

    // renderer
    JNIEXPORT jboolean JNICALL FN_ANDROID(nAttachAndroidSurface)(JNIEnv *env, jclass clazz, jlong ptr, jobject surface);
    JNIEXPORT jboolean JNICALL FN_ANDROID(nDetachAndroidSurface)(JNIEnv *env, jclass clazz, jlong ptr);

#ifdef _WIN32
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nCreateRenderContextD3D11)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nDestroyRenderContextD3D11)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nSetSurfaceConfigD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jint width, jint height, jlong skiko_device_ptr);
	JNIEXPORT jlong JNICALL FN_DESKTOP(nGetFrameStateD3D11)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jlong JNICALL FN_DESKTOP(nGetBufferTextureD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jint index);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nAckRetiredBuffersD3D11)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nHasD3D11Surface)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nSaveSurfacePngD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jstring path);
	JNIEXPORT jintArray JNICALL FN_DESKTOP(nReadSurfacePixelsD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jintArray dims);
#endif

#ifdef __APPLE__
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nCreateRenderContextMacos)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nDestroyRenderContextMacos)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nSetSurfaceConfigMacos)(JNIEnv *env, jclass clazz, jlong ptr, jint width, jint height, jlong mtl_device_ptr);
	JNIEXPORT jlong JNICALL FN_DESKTOP(nGetFrameStateMacos)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jlong JNICALL FN_DESKTOP(nGetBufferTextureMacos)(JNIEnv *env, jclass clazz, jlong ptr, jint index);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nAckRetiredBuffersMacos)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nHasMetalSurface)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nSaveSurfacePng)(JNIEnv *env, jclass clazz, jlong ptr, jstring path);
	JNIEXPORT jintArray JNICALL FN_DESKTOP(nReadSurfacePixelsMacos)(JNIEnv *env, jclass clazz, jlong ptr, jintArray dims);
#endif

	/**
	 * 关闭此 mpv_handle_t 实例
	 */
    JNIEXPORT jboolean JNICALL FN(nDestroy)(JNIEnv *env, jclass clazz, jlong ptr);
    /**
     * 被 GC 调用，回收 mpv_handle_t
     */
    JNIEXPORT void JNICALL FN(nFinalize)(JNIEnv *env, jclass clazz, jlong ptr);
}

// implementations

JNIEXPORT jboolean JNICALL FN(nGlobalInit)(JNIEnv *env, jclass clazz) {
    return true;
}

JNIEXPORT jlong JNICALL FN(nMake)(JNIEnv *env, jclass clazz, jobject app_context) {
    try {
        auto* handle = new mediampv::mpv_handle_t(env, app_context);
        return reinterpret_cast<jlong>(handle);
    } catch (const std::exception &e) {
        // A C++ exception unwinding across the JNI boundary is undefined behavior. Translate
        // it into an IllegalStateException carrying the concrete reason (out of memory, mpv
        // init error, ...) so the JVM caller learns exactly why creation failed instead of
        // seeing a bare 0. Not logged: the exception is the single report of the failure.
        mediampv::throw_illegal_state(env, e.what());
        return 0;
    } catch (...) {
        mediampv::throw_illegal_state(env, "failed to create native mpv handle (unknown error)");
        return 0;
    }
}

JNIEXPORT jboolean JNICALL FN(nInitialize)(JNIEnv *env, jclass clazz, jlong ptr) {
    auto *instance = get_instance(ptr);
    if (!instance) {
        mediampv::throw_illegal_state(env, "cannot initialize: native mpv handle is not available");
        return JNI_FALSE;
    }
    try {
        return instance->initialize();
    } catch (const std::exception &e) {
        // initialize() throws on unrecoverable init failure; surface the concrete reason.
        mediampv::throw_illegal_state(env, e.what(), instance);
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL FN(nSetEventListener)
        (JNIEnv *env, jclass clazz, jlong ptr, jobject listener) {
    auto *instance = get_instance(ptr);
    return instance ? instance->set_event_listener(env, listener) : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN(nSetRenderUpdateListener)
        (JNIEnv *env, jclass clazz, jlong ptr, jobject listener) {
    auto *instance = get_instance(ptr);
    return instance ? instance->set_render_update_listener(env, listener) : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN(nCommand)(JNIEnv *env, jclass clazz, jlong ptr, jobjectArray args) {
    auto *instance = get_instance(ptr);
    if (!instance || !args) {
        return JNI_FALSE;
    }

    const jsize len = env->GetArrayLength(args);
    if (len >= 128) {
        LOG(instance, mediampv::LOG_LEVEL_ERROR, "nCommand: too many arguments (%d >= 128)", len);
        return JNI_FALSE;
    }

    std::vector<jstring> local_args(static_cast<size_t>(len), nullptr);
    std::vector<const char *> arguments(static_cast<size_t>(len) + 1, nullptr);
    auto release_arguments = [&]() {
        for (jsize i = 0; i < len; ++i) {
            if (local_args[static_cast<size_t>(i)] && arguments[static_cast<size_t>(i)]) {
                env->ReleaseStringUTFChars(local_args[static_cast<size_t>(i)], arguments[static_cast<size_t>(i)]);
            }
            if (local_args[static_cast<size_t>(i)]) {
                env->DeleteLocalRef(local_args[static_cast<size_t>(i)]);
            }
        }
    };

    for (jsize i = 0; i < len; ++i) {
        auto argument = static_cast<jstring>(env->GetObjectArrayElement(args, i));
        if (!argument) {
            release_arguments();
            return JNI_FALSE;
        }
        local_args[static_cast<size_t>(i)] = argument;
        arguments[static_cast<size_t>(i)] = env->GetStringUTFChars(argument, nullptr);
        if (!arguments[static_cast<size_t>(i)]) {
            release_arguments();
            return JNI_FALSE;
        }
    }

    const bool result = instance->command(arguments.data());
    release_arguments();

    return result;
}

JNIEXPORT jboolean JNICALL FN(nOption)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jstring value) {
    auto *instance = get_instance(ptr);
    scoped_utf_chars option_key(env, key);
    scoped_utf_chars option_value(env, value);
    if (!instance || !option_key.valid() || !option_value.valid()) {
        return JNI_FALSE;
    }

    return instance->set_option(option_key.get(), option_value.get());
}

// property set and get

JNIEXPORT jint JNICALL FN(nGetPropertyInt)(JNIEnv *env, jclass clazz, jlong ptr, jstring key) {
    auto *instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    if (!instance || !property_key.valid()) {
        return 0;
    }

    int64_t result = 0;
    if (!instance->get_property(property_key.get(), MPV_FORMAT_INT64, &result)) {
        return 0;
    }

    if (result > std::numeric_limits<jint>::max()) {
        return std::numeric_limits<jint>::max();
    }
    if (result < std::numeric_limits<jint>::min()) {
        return std::numeric_limits<jint>::min();
    }
    return static_cast<jint>(result);
}

JNIEXPORT jdouble JNICALL FN(nGetPropertyDouble)(JNIEnv *env, jclass clazz, jlong ptr, jstring key) {
    auto *instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    if (!instance || !property_key.valid()) {
        return 0;
    }

    double result = 0;
    instance->get_property(property_key.get(), MPV_FORMAT_DOUBLE, &result);

    return result;
}

JNIEXPORT jboolean JNICALL FN(nGetPropertyBoolean)(JNIEnv *env, jclass clazz, jlong ptr, jstring key) {
    auto *instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    if (!instance || !property_key.valid()) {
        return JNI_FALSE;
    }

    int result = 0;
    instance->get_property(property_key.get(), MPV_FORMAT_FLAG, &result);

    return result != 0;
}

JNIEXPORT jstring JNICALL FN(nGetPropertyString)(JNIEnv *env, jclass clazz, jlong ptr, jstring key) {
    auto *instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    if (!instance || !property_key.valid()) {
        return nullptr;
    }

    char *result = nullptr;
    if (!instance->get_property(property_key.get(), MPV_FORMAT_STRING, &result) || !result) {
        if (result) {
            mpv_free(result);
        }
        return nullptr;
    }

    jstring jresult = env->NewStringUTF(result);
    mpv_free(result);

    return jresult;
}

JNIEXPORT jboolean JNICALL FN(nSetPropertyString)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jstring value) {
    auto *instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    scoped_utf_chars property_value(env, value);
    if (!instance || !property_key.valid() || !property_value.valid()) {
        return JNI_FALSE;
    }

    const char *value_chars = property_value.get();
    return instance->set_property(property_key.get(), MPV_FORMAT_STRING, &value_chars);
}

JNIEXPORT jboolean JNICALL FN(nSetPropertyInt)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jint value) {
    auto *instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    if (!instance || !property_key.valid()) {
        return JNI_FALSE;
    }

    int64_t native_value = value;
    return instance->set_property(property_key.get(), MPV_FORMAT_INT64, &native_value);
}

JNIEXPORT jboolean JNICALL FN(nSetPropertyDouble)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jdouble value) {
    auto *instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    if (!instance || !property_key.valid()) {
        return JNI_FALSE;
    }

    return instance->set_property(property_key.get(), MPV_FORMAT_DOUBLE, &value);
}

JNIEXPORT jboolean JNICALL FN(nSetPropertyBoolean)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jboolean value) {
    auto *instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    if (!instance || !property_key.valid()) {
        return JNI_FALSE;
    }

    int native_value = value == JNI_TRUE ? 1 : 0;
    return instance->set_property(property_key.get(), MPV_FORMAT_FLAG, &native_value);
}

JNIEXPORT jboolean JNICALL FN(nObserveProperty)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jint format, jlong reply_data) {
    auto *instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    if (!instance || !property_key.valid()) {
        return JNI_FALSE;
    }

    return instance->observe_property(property_key.get(), static_cast<mpv_format>(format), reply_data);
}

JNIEXPORT jboolean JNICALL FN(nUnobserveProperty)(JNIEnv *env, jclass clazz, jlong ptr, jlong reply_data) {
    auto *instance = get_instance(ptr);
    return instance ? instance->unobserve_property(reply_data) : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN(nRegisterSeekableInput)(JNIEnv *env, jclass clazz, jlong ptr, jobject input, jstring uri, jlong size) {
    auto *instance = get_instance(ptr);
    scoped_utf_chars stream_uri(env, uri);
    if (!instance || !stream_uri.valid()) {
        return JNI_FALSE;
    }

    return instance->register_seekable_input(env, input, stream_uri.get(), static_cast<int64_t>(size));
}

JNIEXPORT jboolean JNICALL FN(nUnregisterSeekableInput)(JNIEnv *env, jclass clazz, jlong ptr, jstring uri) {
    auto *instance = get_instance(ptr);
    scoped_utf_chars stream_uri(env, uri);
    if (!instance || !stream_uri.valid()) {
        return JNI_FALSE;
    }

    return instance->unregister_seekable_input(stream_uri.get());
}

JNIEXPORT jboolean JNICALL FN_ANDROID(nAttachAndroidSurface)(JNIEnv *env, jclass clazz, jlong ptr, jobject surface) {
    auto *instance = get_instance(ptr);
    return instance ? instance->attach_android_surface(env, surface) : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN_ANDROID(nDetachAndroidSurface)(JNIEnv *env, jclass clazz, jlong ptr) {
    auto *instance = get_instance(ptr);
    return instance ? instance->detach_android_surface(env) : JNI_FALSE;
}

#ifdef _WIN32

JNIEXPORT jboolean JNICALL FN_DESKTOP(nCreateRenderContextD3D11)(JNIEnv * env, jclass clazz, jlong ptr) {
    auto *instance = get_instance(ptr);
    return instance ? instance->create_render_context() : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nDestroyRenderContextD3D11)(JNIEnv * env, jclass clazz, jlong ptr) {
    auto *instance = get_instance(ptr);
    return instance ? instance->destroy_render_context() : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nSetSurfaceConfigD3D11)(JNIEnv * env, jclass clazz, jlong ptr, jint width, jint height, jlong skiko_device_ptr) {
    auto *instance = get_instance(ptr);
    return instance ? instance->set_surface_config(width, height, skiko_device_ptr) : JNI_FALSE;
}

JNIEXPORT jlong JNICALL FN_DESKTOP(nGetFrameStateD3D11)(JNIEnv * env, jclass clazz, jlong ptr) {
    auto *instance = get_instance(ptr);
    return instance ? (jlong) instance->get_frame_state() : 0;
}

JNIEXPORT jlong JNICALL FN_DESKTOP(nGetBufferTextureD3D11)(JNIEnv * env, jclass clazz, jlong ptr, jint index) {
    auto *instance = get_instance(ptr);
    return instance ? instance->get_buffer_texture(index) : 0;
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nAckRetiredBuffersD3D11)(JNIEnv * env, jclass clazz, jlong ptr) {
    auto *instance = get_instance(ptr);
    return instance ? instance->ack_retired_buffers() : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nHasD3D11Surface)(JNIEnv * env, jclass clazz, jlong ptr) {
    auto *instance = get_instance(ptr);
    return instance ? instance->has_d3d11_surface() : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nSaveSurfacePngD3D11)(JNIEnv * env, jclass clazz, jlong ptr, jstring path) {
    auto *instance = get_instance(ptr);
    if (!instance) {
        return JNI_FALSE;
    }
    scoped_utf_chars path_chars(env, path);
    if (!path_chars.valid()) {
        return JNI_FALSE;
    }
    return instance->save_surface_png(path_chars.get());
}

JNIEXPORT jintArray JNICALL FN_DESKTOP(nReadSurfacePixelsD3D11)(JNIEnv * env, jclass clazz, jlong ptr, jintArray dims) {
    return read_surface_pixels_to_java(env, ptr, dims);
}

#endif

#ifdef __APPLE__

JNIEXPORT jboolean JNICALL FN_DESKTOP(nCreateRenderContextMacos)(JNIEnv * env, jclass clazz, jlong ptr) {
    auto *instance = get_instance(ptr);
    return instance ? instance->create_render_context() : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nDestroyRenderContextMacos)(JNIEnv * env, jclass clazz, jlong ptr) {
    auto *instance = get_instance(ptr);
    return instance ? instance->destroy_render_context() : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nSetSurfaceConfigMacos)(JNIEnv * env, jclass clazz, jlong ptr, jint width, jint height, jlong mtl_device_ptr) {
    auto *instance = get_instance(ptr);
    return instance ? instance->set_surface_config(width, height, mtl_device_ptr) : JNI_FALSE;
}

JNIEXPORT jlong JNICALL FN_DESKTOP(nGetFrameStateMacos)(JNIEnv * env, jclass clazz, jlong ptr) {
    auto *instance = get_instance(ptr);
    return instance ? static_cast<jlong>(instance->get_frame_state()) : 0;
}

JNIEXPORT jlong JNICALL FN_DESKTOP(nGetBufferTextureMacos)(JNIEnv * env, jclass clazz, jlong ptr, jint index) {
    auto *instance = get_instance(ptr);
    return instance ? static_cast<jlong>(instance->get_buffer_texture(index)) : 0;
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nAckRetiredBuffersMacos)(JNIEnv * env, jclass clazz, jlong ptr) {
    auto *instance = get_instance(ptr);
    return instance ? instance->ack_retired_buffers() : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nHasMetalSurface)(JNIEnv * env, jclass clazz, jlong ptr) {
    auto *instance = get_instance(ptr);
    return instance ? instance->has_metal_surface() : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nSaveSurfacePng)(JNIEnv * env, jclass clazz, jlong ptr, jstring path) {
    auto *instance = get_instance(ptr);
    if (!instance) {
        return JNI_FALSE;
    }
    // GetStringUTFChars can return null (OOM); scoped_utf_chars.valid() guards it and
    // pairs the release, matching the Windows nSaveSurfacePngD3D11 path.
    scoped_utf_chars path_chars(env, path);
    if (!path_chars.valid()) {
        return JNI_FALSE;
    }
    return instance->save_surface_png(path_chars.get());
}

JNIEXPORT jintArray JNICALL FN_DESKTOP(nReadSurfacePixelsMacos)(JNIEnv * env, jclass clazz, jlong ptr, jintArray dims) {
    return read_surface_pixels_to_java(env, ptr, dims);
}

#endif

JNIEXPORT jboolean JNICALL FN(nDestroy)(JNIEnv *env, jclass clazz, jlong ptr) {
    auto *instance = get_instance(ptr);
    return instance ? instance->destroy(env) : JNI_FALSE;
}

JNIEXPORT void JNICALL FN(nFinalize)(JNIEnv *env, jclass clazz, jlong ptr) {
    auto *instance = get_instance(ptr);
    if (!instance) {
        return;
    }
    delete instance;
}
