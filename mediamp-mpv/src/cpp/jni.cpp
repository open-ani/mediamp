#include "platform.h"
#include <iostream>
#include <cstdint>
#include <limits>
#include <vector>
#include <jni.h>
#include <cstdio>
#ifdef MEDIAMPV_LINUX_DESKTOP
#define GL_GLEXT_PROTOTYPES 1
#include <dlfcn.h>
#include <GL/gl.h>
#include <GL/glext.h>
#include <GL/glx.h>
#include <X11/Xlib.h>
#include <jawt.h>
#include <jawt_md.h>
#endif
#include "mpv_handle_t.h"
#include "method_cache.h"
#include "handle_registry.h"

#define FN(name) Java_org_openani_mediamp_mpv_MPVHandleKt_##name
#define FN_ANDROID(name) Java_org_openani_mediamp_mpv_MPVHandleAndroid_##name
#define FN_DESKTOP(name) Java_org_openani_mediamp_mpv_MPVHandleDesktop_##name

namespace {

// A C++ exception unwinding into the JVM is undefined behavior (in practice an abort).
// Every entry point below is a function-try-block ending in JNI_CATCH, which turns any
// escaping exception (bad_alloc, system_error from a thread start, ...) into an
// IllegalStateException carrying what() and returns the call's "unavailable" value.
void report_native_exception(JNIEnv *env) noexcept {
    try {
        throw;
    } catch (const std::exception &e) {
        mediampv::throw_illegal_state(env, e.what());
    } catch (...) {
        mediampv::throw_illegal_state(env, "unexpected native error");
    }
}

#define JNI_CATCH(fallback) catch (...) { report_native_exception(env); return fallback; }
#define JNI_CATCH_VOID catch (...) { report_native_exception(env); }

// `ptr` is the opaque id from nMake (see handle_registry.h). The returned reference keeps
// the instance alive for the whole call; null once the player has been finalized.
std::shared_ptr<mediampv::mpv_handle_t> get_instance(jlong ptr) {
    return mediampv::find_handle(ptr);
}

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

#ifdef MEDIAMPV_DESKTOP
// The player's current renderer, keeping the player alive alongside it for the call.
struct renderer_ref {
    std::shared_ptr<mediampv::mpv_handle_t> instance;
    std::shared_ptr<mediampv::desktop_renderer> renderer;

    mediampv::desktop_renderer *operator->() const { return renderer.get(); }
    explicit operator bool() const { return renderer != nullptr; }
};

renderer_ref get_renderer(jlong ptr) {
    renderer_ref ref;
    ref.instance = get_instance(ptr);
    if (ref.instance) ref.renderer = ref.instance->renderer();
    return ref;
}

jlong frame_state(jlong ptr) {
    auto instance = get_instance(ptr);
    return instance ? static_cast<jlong>(instance->frame_state()) : 0;
}

jlong copy_latest_frame(jlong ptr, jlong dest_addr, jint width, jint height) {
    auto renderer = get_renderer(ptr);
    if (!renderer || dest_addr == 0) return 0;
    return static_cast<jlong>(renderer->copy_latest_frame(
        reinterpret_cast<void *>(static_cast<uintptr_t>(dest_addr)), width, height));
}

jboolean save_surface_png(JNIEnv *env, jlong ptr, jstring path) {
    auto renderer = get_renderer(ptr);
    if (!renderer) return JNI_FALSE;
    // GetStringUTFChars can return null (OOM); valid() guards it and pairs the release.
    scoped_utf_chars path_chars(env, path);
    return path_chars.valid() && renderer->save_surface_png(path_chars.get()) ? JNI_TRUE : JNI_FALSE;
}

// Shared body of nReadSurfacePixels*: the latest frame as an ARGB jintArray, writing
// [width, height] into dims, or null when no frame is available.
jintArray read_surface_pixels_to_java(JNIEnv *env, jlong ptr, jintArray dims) {
    auto renderer = get_renderer(ptr);
    if (!renderer || !dims || env->GetArrayLength(dims) < 2) {
        return nullptr;
    }
    std::vector<uint32_t> pixels;
    int width = 0, height = 0;
    if (!renderer->read_surface_pixels(pixels, width, height) || pixels.empty()) {
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

} // namespace

extern "C" {
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
    // Like nGetProperty*, but distinguish "unavailable" (returns false) from a real 0.
    JNIEXPORT jboolean JNICALL FN(nTryGetPropertyLong)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jlongArray out);
    JNIEXPORT jboolean JNICALL FN(nTryGetPropertyDouble)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jdoubleArray out);
    JNIEXPORT jboolean JNICALL FN(nTryGetPropertyBoolean)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jbooleanArray out);

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
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nSetConsumerDeviceHintD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jlong skiko_device_ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nSetReadbackSurfaceConfigD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jint width, jint height);
	JNIEXPORT jlong JNICALL FN_DESKTOP(nCopyLatestFrameD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jlong dest_addr, jint width, jint height);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nSetSurfaceConfigD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jint width, jint height, jlong skiko_device_ptr);
	JNIEXPORT jlong JNICALL FN_DESKTOP(nGetFrameStateD3D11)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jlong JNICALL FN_DESKTOP(nGetBufferTextureD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jint index);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nAckRetiredBuffersD3D11)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nHasD3D11Surface)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nSaveSurfacePngD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jstring path);
	JNIEXPORT jintArray JNICALL FN_DESKTOP(nReadSurfacePixelsD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jintArray dims);

	// Windows OpenGL fallback render path (render_opengl_win.cpp), used when Compose
	// renders with Skiko's OpenGL backend instead of Direct3D.
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nCreateRenderContextWindowsOpenGL)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nDestroyRenderContextWindowsOpenGL)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nSetSurfaceConfigWindowsOpenGL)(JNIEnv *env, jclass clazz, jlong ptr, jint width, jint height);
	JNIEXPORT jlong JNICALL FN_DESKTOP(nGetFrameStateWindowsOpenGL)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nHasWindowsOpenGLSurface)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nSaveSurfacePngWindowsOpenGL)(JNIEnv *env, jclass clazz, jlong ptr, jstring path);
	JNIEXPORT jintArray JNICALL FN_DESKTOP(nReadSurfacePixelsWindowsOpenGL)(JNIEnv *env, jclass clazz, jlong ptr, jintArray dims);
	JNIEXPORT jlong JNICALL FN_DESKTOP(nCopyLatestFrameWindowsOpenGL)(JNIEnv *env, jclass clazz, jlong ptr, jlong dest_addr, jint width, jint height);
#endif

#ifdef __APPLE__
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nCreateRenderContextMacos)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nDestroyRenderContextMacos)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nSetSurfaceConfigMacos)(JNIEnv *env, jclass clazz, jlong ptr, jint width, jint height, jlong mtl_device_ptr);
	JNIEXPORT jlong JNICALL FN_DESKTOP(nGetFrameStateMacos)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jlong JNICALL FN_DESKTOP(nGetBufferTextureMacos)(JNIEnv *env, jclass clazz, jlong ptr, jint index);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nAckRetiredBuffersMacos)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nHasMetalSurface)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nSaveSurfacePngMacos)(JNIEnv *env, jclass clazz, jlong ptr, jstring path);
	JNIEXPORT jintArray JNICALL FN_DESKTOP(nReadSurfacePixelsMacos)(JNIEnv *env, jclass clazz, jlong ptr, jintArray dims);
#endif

#ifdef MEDIAMPV_LINUX_DESKTOP
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nAttachRenderEnvironmentOpenGL)(JNIEnv *env, jclass clazz, jlong ptr, jobject component, jlong share_context, jlong drawable, jlong window);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nCreateRenderContextOpenGL)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nDestroyRenderContextOpenGL)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nSetSurfaceConfigOpenGL)(JNIEnv *env, jclass clazz, jlong ptr, jint width, jint height, jlong consumer_environment_ptr);
	JNIEXPORT jlong JNICALL FN_DESKTOP(nGetFrameStateOpenGL)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jlong JNICALL FN_DESKTOP(nGetBufferTextureOpenGL)(JNIEnv *env, jclass clazz, jlong ptr, jint index);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nAckRetiredBuffersOpenGL)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nHasOpenGLSurface)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nSaveSurfacePngOpenGL)(JNIEnv *env, jclass clazz, jlong ptr, jstring path);
	JNIEXPORT jintArray JNICALL FN_DESKTOP(nReadSurfacePixelsOpenGL)(JNIEnv *env, jclass clazz, jlong ptr, jintArray dims);
	JNIEXPORT jint JNICALL FN_DESKTOP(nCreateOpenGLConsumerFbo)(JNIEnv *env, jclass clazz, jlong texture_name);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nDeleteOpenGLConsumerFbo)(JNIEnv *env, jclass clazz, jint fbo);
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

JNIEXPORT jlong JNICALL FN(nMake)(JNIEnv *env, jclass clazz, jobject app_context) try {
    try {
        return mediampv::register_handle(std::make_unique<mediampv::mpv_handle_t>(env, app_context));
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
} JNI_CATCH(0)

JNIEXPORT jboolean JNICALL FN(nInitialize)(JNIEnv *env, jclass clazz, jlong ptr) try {
    auto instance = get_instance(ptr);
    if (!instance) {
        mediampv::throw_illegal_state(env, "cannot initialize: native mpv handle is not available");
        return JNI_FALSE;
    }
    try {
        return instance->initialize();
    } catch (const std::exception &e) {
        // initialize() throws on unrecoverable init failure; surface the concrete reason.
        mediampv::throw_illegal_state(env, e.what(), instance.get());
        return JNI_FALSE;
    }
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN(nSetEventListener)
        (JNIEnv *env, jclass clazz, jlong ptr, jobject listener) try {
    auto instance = get_instance(ptr);
    return instance ? instance->set_event_listener(env, listener) : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN(nSetRenderUpdateListener)
        (JNIEnv *env, jclass clazz, jlong ptr, jobject listener) try {
    auto instance = get_instance(ptr);
    return instance ? instance->set_render_update_listener(env, listener) : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN(nCommand)(JNIEnv *env, jclass clazz, jlong ptr, jobjectArray args) try {
    auto instance = get_instance(ptr);
    if (!instance || !args) {
        return JNI_FALSE;
    }

    const jsize len = env->GetArrayLength(args);
    if (len >= 128) {
        LOG(instance.get(), mediampv::LOG_LEVEL_ERROR, "nCommand: too many arguments (%d >= 128)", static_cast<int>(len));
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
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN(nOption)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jstring value) try {
    auto instance = get_instance(ptr);
    scoped_utf_chars option_key(env, key);
    scoped_utf_chars option_value(env, value);
    if (!instance || !option_key.valid() || !option_value.valid()) {
        return JNI_FALSE;
    }

    return instance->set_option(option_key.get(), option_value.get());
} JNI_CATCH(JNI_FALSE)

// property set and get

JNIEXPORT jint JNICALL FN(nGetPropertyInt)(JNIEnv *env, jclass clazz, jlong ptr, jstring key) try {
    auto instance = get_instance(ptr);
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
} JNI_CATCH(0)

JNIEXPORT jdouble JNICALL FN(nGetPropertyDouble)(JNIEnv *env, jclass clazz, jlong ptr, jstring key) try {
    auto instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    if (!instance || !property_key.valid()) {
        return 0;
    }

    double result = 0;
    instance->get_property(property_key.get(), MPV_FORMAT_DOUBLE, &result);

    return result;
} JNI_CATCH(0)

JNIEXPORT jboolean JNICALL FN(nGetPropertyBoolean)(JNIEnv *env, jclass clazz, jlong ptr, jstring key) try {
    auto instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    if (!instance || !property_key.valid()) {
        return JNI_FALSE;
    }

    int result = 0;
    instance->get_property(property_key.get(), MPV_FORMAT_FLAG, &result);

    return result != 0;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jstring JNICALL FN(nGetPropertyString)(JNIEnv *env, jclass clazz, jlong ptr, jstring key) try {
    auto instance = get_instance(ptr);
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
} JNI_CATCH(nullptr)

JNIEXPORT jboolean JNICALL FN(nTryGetPropertyLong)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jlongArray out) try {
    auto instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    if (!instance || !property_key.valid() || !out || env->GetArrayLength(out) < 1) {
        return JNI_FALSE;
    }
    int64_t value = 0;
    if (!instance->get_property(property_key.get(), MPV_FORMAT_INT64, &value)) {
        return JNI_FALSE;
    }
    const jlong result = value;
    env->SetLongArrayRegion(out, 0, 1, &result);
    return JNI_TRUE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN(nTryGetPropertyDouble)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jdoubleArray out) try {
    auto instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    if (!instance || !property_key.valid() || !out || env->GetArrayLength(out) < 1) {
        return JNI_FALSE;
    }
    double value = 0;
    if (!instance->get_property(property_key.get(), MPV_FORMAT_DOUBLE, &value)) {
        return JNI_FALSE;
    }
    env->SetDoubleArrayRegion(out, 0, 1, &value);
    return JNI_TRUE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN(nTryGetPropertyBoolean)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jbooleanArray out) try {
    auto instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    if (!instance || !property_key.valid() || !out || env->GetArrayLength(out) < 1) {
        return JNI_FALSE;
    }
    int value = 0;
    if (!instance->get_property(property_key.get(), MPV_FORMAT_FLAG, &value)) {
        return JNI_FALSE;
    }
    const jboolean result = value != 0 ? JNI_TRUE : JNI_FALSE;
    env->SetBooleanArrayRegion(out, 0, 1, &result);
    return JNI_TRUE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN(nSetPropertyString)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jstring value) try {
    auto instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    scoped_utf_chars property_value(env, value);
    if (!instance || !property_key.valid() || !property_value.valid()) {
        return JNI_FALSE;
    }

    const char *value_chars = property_value.get();
    return instance->set_property(property_key.get(), MPV_FORMAT_STRING, &value_chars);
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN(nSetPropertyInt)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jint value) try {
    auto instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    if (!instance || !property_key.valid()) {
        return JNI_FALSE;
    }

    int64_t native_value = value;
    return instance->set_property(property_key.get(), MPV_FORMAT_INT64, &native_value);
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN(nSetPropertyDouble)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jdouble value) try {
    auto instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    if (!instance || !property_key.valid()) {
        return JNI_FALSE;
    }

    return instance->set_property(property_key.get(), MPV_FORMAT_DOUBLE, &value);
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN(nSetPropertyBoolean)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jboolean value) try {
    auto instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    if (!instance || !property_key.valid()) {
        return JNI_FALSE;
    }

    int native_value = value == JNI_TRUE ? 1 : 0;
    return instance->set_property(property_key.get(), MPV_FORMAT_FLAG, &native_value);
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN(nObserveProperty)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jint format, jlong reply_data) try {
    auto instance = get_instance(ptr);
    scoped_utf_chars property_key(env, key);
    if (!instance || !property_key.valid()) {
        return JNI_FALSE;
    }

    return instance->observe_property(property_key.get(), static_cast<mpv_format>(format), reply_data);
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN(nUnobserveProperty)(JNIEnv *env, jclass clazz, jlong ptr, jlong reply_data) try {
    auto instance = get_instance(ptr);
    return instance ? instance->unobserve_property(reply_data) : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN(nRegisterSeekableInput)(JNIEnv *env, jclass clazz, jlong ptr, jobject input, jstring uri, jlong size) try {
    auto instance = get_instance(ptr);
    scoped_utf_chars stream_uri(env, uri);
    if (!instance || !stream_uri.valid()) {
        return JNI_FALSE;
    }

    return instance->register_seekable_input(env, input, stream_uri.get(), static_cast<int64_t>(size));
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN(nUnregisterSeekableInput)(JNIEnv *env, jclass clazz, jlong ptr, jstring uri) try {
    auto instance = get_instance(ptr);
    scoped_utf_chars stream_uri(env, uri);
    if (!instance || !stream_uri.valid()) {
        return JNI_FALSE;
    }

    return instance->unregister_seekable_input(stream_uri.get());
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_ANDROID(nAttachAndroidSurface)(JNIEnv *env, jclass clazz, jlong ptr, jobject surface) try {
    auto instance = get_instance(ptr);
    return instance ? instance->attach_android_surface(env, surface) : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_ANDROID(nDetachAndroidSurface)(JNIEnv *env, jclass clazz, jlong ptr) try {
    auto instance = get_instance(ptr);
    return instance ? instance->detach_android_surface(env) : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

#ifdef _WIN32

JNIEXPORT jboolean JNICALL FN_DESKTOP(nCreateRenderContextD3D11)(JNIEnv *env, jclass clazz, jlong ptr) try {
    auto instance = get_instance(ptr);
    return instance && instance->create_d3d11_renderer() ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nDestroyRenderContextD3D11)(JNIEnv *env, jclass clazz, jlong ptr) try {
    auto instance = get_instance(ptr);
    return instance && instance->destroy_renderer() ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nSetConsumerDeviceHintD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jlong skiko_device_ptr) try {
    auto instance = get_instance(ptr);
    return instance && instance->set_consumer_device_hint(skiko_device_ptr) ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nSetReadbackSurfaceConfigD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jint width, jint height) try {
    auto renderer = get_renderer(ptr);
    return renderer && renderer->set_readback_surface_config(width, height) ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jlong JNICALL FN_DESKTOP(nCopyLatestFrameD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jlong dest_addr, jint width, jint height) try {
    return copy_latest_frame(ptr, dest_addr, width, height);
} JNI_CATCH(0)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nSetSurfaceConfigD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jint width, jint height, jlong skiko_device_ptr) try {
    auto renderer = get_renderer(ptr);
    return renderer && renderer->set_surface_config(width, height, skiko_device_ptr) ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jlong JNICALL FN_DESKTOP(nGetFrameStateD3D11)(JNIEnv *env, jclass clazz, jlong ptr) try {
    return frame_state(ptr);
} JNI_CATCH(0)

JNIEXPORT jlong JNICALL FN_DESKTOP(nGetBufferTextureD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jint index) try {
    auto renderer = get_renderer(ptr);
    return renderer ? static_cast<jlong>(renderer->buffer_texture(index)) : 0;
} JNI_CATCH(0)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nAckRetiredBuffersD3D11)(JNIEnv *env, jclass clazz, jlong ptr) try {
    auto renderer = get_renderer(ptr);
    return renderer && renderer->ack_retired_buffers() ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nHasD3D11Surface)(JNIEnv *env, jclass clazz, jlong ptr) try {
    auto renderer = get_renderer(ptr);
    return renderer && renderer->has_surface() ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nSaveSurfacePngD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jstring path) try {
    return save_surface_png(env, ptr, path);
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jintArray JNICALL FN_DESKTOP(nReadSurfacePixelsD3D11)(JNIEnv *env, jclass clazz, jlong ptr, jintArray dims) try {
    return read_surface_pixels_to_java(env, ptr, dims);
} JNI_CATCH(nullptr)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nCreateRenderContextWindowsOpenGL)(JNIEnv *env, jclass clazz, jlong ptr) try {
    auto instance = get_instance(ptr);
    return instance && instance->create_win_gl_renderer() ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nDestroyRenderContextWindowsOpenGL)(JNIEnv *env, jclass clazz, jlong ptr) try {
    auto instance = get_instance(ptr);
    return instance && instance->destroy_renderer() ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nSetSurfaceConfigWindowsOpenGL)(JNIEnv *env, jclass clazz, jlong ptr, jint width, jint height) try {
    auto renderer = get_renderer(ptr);
    return renderer && renderer->set_surface_config(width, height, 0) ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jlong JNICALL FN_DESKTOP(nGetFrameStateWindowsOpenGL)(JNIEnv *env, jclass clazz, jlong ptr) try {
    return frame_state(ptr);
} JNI_CATCH(0)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nHasWindowsOpenGLSurface)(JNIEnv *env, jclass clazz, jlong ptr) try {
    auto renderer = get_renderer(ptr);
    return renderer && renderer->has_surface() ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nSaveSurfacePngWindowsOpenGL)(JNIEnv *env, jclass clazz, jlong ptr, jstring path) try {
    return save_surface_png(env, ptr, path);
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jintArray JNICALL FN_DESKTOP(nReadSurfacePixelsWindowsOpenGL)(JNIEnv *env, jclass clazz, jlong ptr, jintArray dims) try {
    return read_surface_pixels_to_java(env, ptr, dims);
} JNI_CATCH(nullptr)

JNIEXPORT jlong JNICALL FN_DESKTOP(nCopyLatestFrameWindowsOpenGL)(JNIEnv *env, jclass clazz, jlong ptr, jlong dest_addr, jint width, jint height) try {
    return copy_latest_frame(ptr, dest_addr, width, height);
} JNI_CATCH(0)

#endif

#ifdef __APPLE__

JNIEXPORT jboolean JNICALL FN_DESKTOP(nCreateRenderContextMacos)(JNIEnv *env, jclass clazz, jlong ptr) try {
    auto instance = get_instance(ptr);
    return instance && instance->create_macos_renderer() ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nDestroyRenderContextMacos)(JNIEnv *env, jclass clazz, jlong ptr) try {
    auto instance = get_instance(ptr);
    return instance && instance->destroy_renderer() ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nSetSurfaceConfigMacos)(JNIEnv *env, jclass clazz, jlong ptr, jint width, jint height, jlong mtl_device_ptr) try {
    auto renderer = get_renderer(ptr);
    return renderer && renderer->set_surface_config(width, height, mtl_device_ptr) ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jlong JNICALL FN_DESKTOP(nGetFrameStateMacos)(JNIEnv *env, jclass clazz, jlong ptr) try {
    return frame_state(ptr);
} JNI_CATCH(0)

JNIEXPORT jlong JNICALL FN_DESKTOP(nGetBufferTextureMacos)(JNIEnv *env, jclass clazz, jlong ptr, jint index) try {
    auto renderer = get_renderer(ptr);
    return renderer ? static_cast<jlong>(renderer->buffer_texture(index)) : 0;
} JNI_CATCH(0)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nAckRetiredBuffersMacos)(JNIEnv *env, jclass clazz, jlong ptr) try {
    auto renderer = get_renderer(ptr);
    return renderer && renderer->ack_retired_buffers() ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nHasMetalSurface)(JNIEnv *env, jclass clazz, jlong ptr) try {
    auto renderer = get_renderer(ptr);
    return renderer && renderer->has_surface() ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nSaveSurfacePngMacos)(JNIEnv *env, jclass clazz, jlong ptr, jstring path) try {
    return save_surface_png(env, ptr, path);
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jintArray JNICALL FN_DESKTOP(nReadSurfacePixelsMacos)(JNIEnv *env, jclass clazz, jlong ptr, jintArray dims) try {
    return read_surface_pixels_to_java(env, ptr, dims);
} JNI_CATCH(nullptr)

#endif

#ifdef MEDIAMPV_LINUX_DESKTOP

JNIEXPORT jboolean JNICALL FN_DESKTOP(nAttachRenderEnvironmentOpenGL)(
        JNIEnv *env, jclass, jlong ptr, jobject component,
        jlong share_context, jlong drawable, jlong window) try {
    auto instance = get_instance(ptr);
    if (!instance || !component || !share_context || !drawable) return JNI_FALSE;

    // Resolve JAWT lazily so the JNI wrapper does not acquire a deployment-time
    // dependency on one particular JDK installation path.
    using jawt_get_awt_fn = jboolean (JNICALL *)(JNIEnv *, JAWT *);
    static void *const jawt_library = dlopen("libjawt.so", RTLD_NOW | RTLD_LOCAL);
    static auto get_awt = reinterpret_cast<jawt_get_awt_fn>(
        dlsym(RTLD_DEFAULT, "JAWT_GetAWT"));
    if (!get_awt && jawt_library) {
        get_awt = reinterpret_cast<jawt_get_awt_fn>(dlsym(jawt_library, "JAWT_GetAWT"));
    }
    if (!get_awt) return JNI_FALSE;

    JAWT awt{};
    awt.version = JAWT_VERSION_1_4;
    if (get_awt(env, &awt) == JNI_FALSE) return JNI_FALSE;
    JAWT_DrawingSurface *surface = awt.GetDrawingSurface(env, component);
    if (!surface) return JNI_FALSE;
    const jint lock_result = surface->Lock(surface);
    if ((lock_result & JAWT_LOCK_ERROR) != 0) {
        awt.FreeDrawingSurface(surface);
        return JNI_FALSE;
    }
    JAWT_DrawingSurfaceInfo *surface_info = surface->GetDrawingSurfaceInfo(surface);
    auto *x11 = surface_info
        ? static_cast<JAWT_X11DrawingSurfaceInfo *>(surface_info->platformInfo)
        : nullptr;
    const bool matches_live_layer = x11 && x11->display &&
        static_cast<jlong>(x11->drawable) == drawable;
    const int screen = matches_live_layer ? DefaultScreen(x11->display) : 0;
    // Skiko 0.9.37.4 stores LinuxOpenGLRedrawer.context as a native GLXContext*
    // allocation. Its own makeCurrent/destroyContext JNI entry points dereference that
    // slot before calling GLX; mirror that ABI here instead of passing the slot address
    // to the driver as though it were a GLXContext.
    auto *share_context_slot = reinterpret_cast<GLXContext *>(
        static_cast<uintptr_t>(share_context));
    const GLXContext actual_share_context = share_context_slot ? *share_context_slot : nullptr;
    const uint64_t identity =
        static_cast<uint64_t>(share_context) ^
        (static_cast<uint64_t>(drawable) << 17) ^
        (static_cast<uint64_t>(window) << 33);
    const bool attached = matches_live_layer && actual_share_context && identity != 0 &&
        instance->attach_opengl_render_environment(
            reinterpret_cast<int64_t>(x11->display),
            reinterpret_cast<int64_t>(actual_share_context), screen, identity);
    if (surface_info) surface->FreeDrawingSurfaceInfo(surface_info);
    surface->Unlock(surface);
    awt.FreeDrawingSurface(surface);
    return attached ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nCreateRenderContextOpenGL)(JNIEnv *env, jclass, jlong ptr) try {
    auto instance = get_instance(ptr);
    return instance && instance->create_glx_renderer() ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nDestroyRenderContextOpenGL)(JNIEnv *env, jclass, jlong ptr) try {
    auto instance = get_instance(ptr);
    return instance && instance->destroy_renderer() ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nSetSurfaceConfigOpenGL)(
        JNIEnv *env, jclass, jlong ptr, jint width, jint height, jlong) try {
    auto renderer = get_renderer(ptr);
    return renderer && renderer->set_surface_config(width, height, 0) ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jlong JNICALL FN_DESKTOP(nGetFrameStateOpenGL)(JNIEnv *env, jclass, jlong ptr) try {
    // "No frame" rather than 0 here: the Linux consumer reads the index field first.
    auto instance = get_instance(ptr);
    return static_cast<jlong>(instance ? instance->frame_state() : mediampv::kNoFrameState);
} JNI_CATCH(static_cast<jlong>(mediampv::kNoFrameState))

JNIEXPORT jlong JNICALL FN_DESKTOP(nGetBufferTextureOpenGL)(JNIEnv *env, jclass, jlong ptr, jint index) try {
    auto renderer = get_renderer(ptr);
    return renderer ? static_cast<jlong>(renderer->buffer_texture(index)) : 0;
} JNI_CATCH(0)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nAckRetiredBuffersOpenGL)(JNIEnv *env, jclass, jlong ptr) try {
    auto renderer = get_renderer(ptr);
    return renderer && renderer->ack_retired_buffers() ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nHasOpenGLSurface)(JNIEnv *env, jclass, jlong ptr) try {
    auto renderer = get_renderer(ptr);
    return renderer && renderer->has_surface() ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nSaveSurfacePngOpenGL)(JNIEnv *env, jclass, jlong ptr, jstring path) try {
    return save_surface_png(env, ptr, path);
} JNI_CATCH(JNI_FALSE)

JNIEXPORT jintArray JNICALL FN_DESKTOP(nReadSurfacePixelsOpenGL)(JNIEnv *env, jclass, jlong ptr, jintArray dims) try {
    return read_surface_pixels_to_java(env, ptr, dims);
} JNI_CATCH(nullptr)

JNIEXPORT jint JNICALL FN_DESKTOP(nCreateOpenGLConsumerFbo)(JNIEnv *env, jclass, jlong texture_name) try {
    if (!texture_name || !glXGetCurrentContext()) return 0;
    GLint previous_fbo = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &previous_fbo);
    GLuint fbo = 0;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(
        GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
        static_cast<GLuint>(texture_name), 0);
    const bool complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(previous_fbo));
    if (!complete || glGetError() != GL_NO_ERROR) {
        if (fbo) glDeleteFramebuffers(1, &fbo);
        return 0;
    }
    return static_cast<jint>(fbo);
} JNI_CATCH(0)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nDeleteOpenGLConsumerFbo)(JNIEnv *env, jclass, jint fbo) try {
    if (fbo <= 0 || !glXGetCurrentContext()) return JNI_FALSE;
    const GLuint name = static_cast<GLuint>(fbo);
    glDeleteFramebuffers(1, &name);
    return glGetError() == GL_NO_ERROR ? JNI_TRUE : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

#endif

JNIEXPORT jboolean JNICALL FN(nDestroy)(JNIEnv *env, jclass clazz, jlong ptr) try {
    auto instance = get_instance(ptr);
    return instance ? instance->destroy(env) : JNI_FALSE;
} JNI_CATCH(JNI_FALSE)

JNIEXPORT void JNICALL FN(nFinalize)(JNIEnv *env, jclass clazz, jlong ptr) try {
    mediampv::unregister_handle(ptr);
} JNI_CATCH_VOID
