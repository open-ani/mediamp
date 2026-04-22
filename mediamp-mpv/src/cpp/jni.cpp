#include <iostream>
#include <cstdint>
#include <limits>
#include <vector>
#include <jni.h>
#include <cstdio>
#include "mpv_handle_t.h"

#define FN(name) Java_org_openani_mediamp_mpv_MPVHandleKt_##name
#define FN_ANDROID(name) Java_org_openani_mediamp_mpv_MPVHandleAndroid_##name
#define FN_DESKTOP(name) Java_org_openani_mediamp_mpv_MPVHandleDesktop_##name

namespace {

mediampv::mpv_handle_t *get_instance(jlong ptr) {
    return reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
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

} // namespace

extern "C" {
    JNIEXPORT jboolean JNICALL FN(nGlobalInit)(JNIEnv *env, jclass clazz);
    JNIEXPORT jlong JNICALL FN(nMake)(JNIEnv *env, jclass clazz, jobject app_context);
    JNIEXPORT jboolean JNICALL FN(nInitialize)(JNIEnv *env, jclass clazz, jlong ptr);
    JNIEXPORT jboolean JNICALL FN(nSetEventListener)(JNIEnv *env, jclass clazz, jlong ptr, jobject listener);

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
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nCreateRenderContext)(JNIEnv *env, jclass clazz, jlong ptr, jlong device_ptr, jlong context_ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nDestroyRenderContext)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jint JNICALL FN_DESKTOP(nCreateTexture)(JNIEnv *env, jclass clazz, jlong ptr, jint width, jint height);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nReleaseTexture)(JNIEnv *env, jclass clazz, jlong ptr);
	JNIEXPORT jboolean JNICALL FN_DESKTOP(nRenderFrameToTexture)(JNIEnv *env, jclass clazz, jlong ptr);
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
    auto* handle = new mediampv::mpv_handle_t(env, app_context);
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jboolean JNICALL FN(nInitialize)(JNIEnv *env, jclass clazz, jlong ptr) {
    auto *instance = get_instance(ptr);
    return instance ? instance->initialize() : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN(nSetEventListener)
        (JNIEnv *env, jclass clazz, jlong ptr, jobject listener) {
    auto *instance = get_instance(ptr);
    return instance ? instance->set_event_listener(env, listener) : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN(nCommand)(JNIEnv *env, jclass clazz, jlong ptr, jobjectArray args) {
    auto *instance = get_instance(ptr);
    if (!instance || !args) {
        return JNI_FALSE;
    }

    const jsize len = env->GetArrayLength(args);
    if (len >= 128) {
        LOG("arguments are too long (>128)");
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

JNIEXPORT jboolean JNICALL FN_DESKTOP(nCreateRenderContext)(JNIEnv * env, jclass clazz, jlong ptr, jlong device_ptr, jlong context_ptr) {
	auto *instance = get_instance(ptr);
    if (!instance) {
        return JNI_FALSE;
    }
    auto device = reinterpret_cast<HDC>(static_cast<uintptr_t>(device_ptr));
    auto context = reinterpret_cast<HGLRC>(static_cast<uintptr_t>(context_ptr));
	return instance->create_render_context(device, context);
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nDestroyRenderContext)(JNIEnv * env, jclass clazz, jlong ptr) {
	auto *instance = get_instance(ptr);
    if (!instance) {
        return JNI_FALSE;
    }
	return instance->destroy_render_context();
}

JNIEXPORT jint JNICALL FN_DESKTOP(nCreateTexture)(JNIEnv * env, jclass clazz, jlong ptr, jint width, jint height) {
	auto *instance = get_instance(ptr);
    return instance ? static_cast<jint>(instance->create_texture(width, height)) : 0;
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nReleaseTexture)(JNIEnv * env, jclass clazz, jlong ptr) {
	auto *instance = get_instance(ptr);
	return instance ? instance->release_texture() : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nRenderFrameToTexture)(JNIEnv * env, jclass clazz, jlong ptr) {
	auto *instance = get_instance(ptr);
	return instance ? instance->render_frame() : JNI_FALSE;
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
