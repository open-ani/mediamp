#include <jni.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "libavutil/log.h"
#if defined(_WIN32)
#define strdup _strdup
#else
#include <dlfcn.h>
#endif

int ffmpegkit_execute(int argc, char **argv);
typedef void (*ffmpegkit_log_callback_fn)(int level, const char *message);
void ffmpegkit_set_log_callback(ffmpegkit_log_callback_fn callback);

typedef int (*ffmpeg_av_jni_set_java_vm_fn)(void *vm, void *log_ctx);
typedef int (*ffmpeg_av_jni_set_android_app_ctx_fn)(void *app_ctx, void *log_ctx);

static JavaVM *g_java_vm = NULL;
static jobject g_log_dispatch_class = NULL;
static jmethodID g_log_dispatch_method = NULL;
#if defined(__ANDROID__)
static jobject g_android_app_context = NULL;
#endif

static void throw_runtime_exception(JNIEnv *env, const char *message) {
    jclass runtime_exception = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (runtime_exception != NULL) {
        (*env)->ThrowNew(env, runtime_exception, message);
    }
}

static void release_log_dispatch(JNIEnv *env) {
    if (g_log_dispatch_class != NULL) {
        (*env)->DeleteGlobalRef(env, g_log_dispatch_class);
        g_log_dispatch_class = NULL;
    }
    g_log_dispatch_method = NULL;
}

static int initialize_log_dispatch(JNIEnv *env, jclass clazz) {
    release_log_dispatch(env);
    g_log_dispatch_class = (*env)->NewGlobalRef(env, clazz);
    if (g_log_dispatch_class == NULL) {
        return -1;
    }
    g_log_dispatch_method = (*env)->GetStaticMethodID(
        env,
        (jclass)g_log_dispatch_class,
        "onNativeLog",
        "(ILjava/lang/String;)V"
    );
    if (g_log_dispatch_method == NULL) {
        release_log_dispatch(env);
        return -1;
    }
    return 0;
}

static void dispatch_log_to_jvm(int level, const char *message) {
    if (g_java_vm == NULL || g_log_dispatch_class == NULL || g_log_dispatch_method == NULL || message == NULL || message[0] == '\0') {
        return;
    }

    JNIEnv *env = NULL;
    int detach_after = 0;
    if ((*g_java_vm)->GetEnv(g_java_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
#if defined(__ANDROID__)
        if ((*g_java_vm)->AttachCurrentThread(g_java_vm, &env, NULL) != JNI_OK) {
            return;
        }
#else
        if ((*g_java_vm)->AttachCurrentThread(g_java_vm, (void **)&env, NULL) != JNI_OK) {
            return;
        }
#endif
        detach_after = 1;
    }

    jstring text = (*env)->NewStringUTF(env, message);
    if (text != NULL) {
        (*env)->CallStaticVoidMethod(env, (jclass)g_log_dispatch_class, g_log_dispatch_method, (jint)level, text);
        (*env)->DeleteLocalRef(env, text);
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }

    if (detach_after) {
        (*g_java_vm)->DetachCurrentThread(g_java_vm);
    }
}

static void ffmpegkit_jvm_log_callback(int level, const char *message) {
    dispatch_log_to_jvm(level, message);
}

static void configure_android_jni_context(JNIEnv *env) {
#if defined(__ANDROID__)
    ffmpeg_av_jni_set_java_vm_fn set_java_vm = (ffmpeg_av_jni_set_java_vm_fn)dlsym(RTLD_DEFAULT, "av_jni_set_java_vm");
    if (set_java_vm != NULL && g_java_vm != NULL) {
        set_java_vm(g_java_vm, NULL);
    }
    ffmpeg_av_jni_set_android_app_ctx_fn set_android_app_ctx =
        (ffmpeg_av_jni_set_android_app_ctx_fn)dlsym(RTLD_DEFAULT, "av_jni_set_android_app_ctx");
    if (set_android_app_ctx != NULL && g_android_app_context != NULL) {
        set_android_app_ctx(g_android_app_context, NULL);
    }
#else
    (void)env;
#endif
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_java_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    g_java_vm = NULL;
}

JNIEXPORT void JNICALL Java_org_openani_mediamp_ffmpeg_JvmFFmpegProcess_initializeAndroidContext(
    JNIEnv *env,
    jclass clazz,
    jobject app_context
);

JNIEXPORT jint JNICALL Java_org_openani_mediamp_ffmpeg_JvmFFmpegProcess_executeNative(
    JNIEnv *env,
    jclass clazz,
    jobjectArray args
);

JNIEXPORT void JNICALL Java_org_openani_mediamp_ffmpeg_JvmFFmpegProcess_initializeAndroidContext(
    JNIEnv *env,
    jclass clazz,
    jobject app_context
) {
    (void)clazz;
#if defined(__ANDROID__)
    if (g_android_app_context != NULL) {
        (*env)->DeleteGlobalRef(env, g_android_app_context);
        g_android_app_context = NULL;
    }
    if (app_context != NULL) {
        g_android_app_context = (*env)->NewGlobalRef(env, app_context);
    }
    configure_android_jni_context(env);
#else
    (void)env;
    (void)app_context;
#endif
}

JNIEXPORT jint JNICALL Java_org_openani_mediamp_ffmpeg_JvmFFmpegProcess_executeNative(
    JNIEnv *env,
    jclass clazz,
    jobjectArray args
) {
    const jsize arg_count = args == NULL ? 0 : (*env)->GetArrayLength(env, args);
    char **argv = (char **)calloc((size_t)arg_count + 2U, sizeof(char *));
    if (argv == NULL) {
        throw_runtime_exception(env, "Failed to allocate FFmpeg argv array.");
        return -1;
    }

    argv[0] = strdup("ffmpeg");
    if (argv[0] == NULL) {
        free(argv);
        throw_runtime_exception(env, "Failed to allocate FFmpeg argv[0].");
        return -1;
    }

    for (jsize index = 0; index < arg_count; ++index) {
        jstring arg = (jstring)(*env)->GetObjectArrayElement(env, args, index);
        const char *arg_chars = arg == NULL ? "" : (*env)->GetStringUTFChars(env, arg, NULL);
        argv[(size_t)index + 1U] = strdup(arg_chars == NULL ? "" : arg_chars);
        if (arg != NULL && arg_chars != NULL) {
            (*env)->ReleaseStringUTFChars(env, arg, arg_chars);
        }
        if (arg != NULL) {
            (*env)->DeleteLocalRef(env, arg);
        }
        if (argv[(size_t)index + 1U] == NULL) {
            for (jsize free_index = 0; free_index <= index; ++free_index) {
                free(argv[(size_t)free_index]);
            }
            free(argv);
            throw_runtime_exception(env, "Failed to allocate FFmpeg argument string.");
            return -1;
        }
    }

    if (initialize_log_dispatch(env, clazz) != 0) {
        for (jsize index = 0; index <= arg_count; ++index) {
            free(argv[(size_t)index]);
        }
        free(argv);
        throw_runtime_exception(env, "Failed to initialize FFmpeg log dispatch.");
        return -1;
    }

    configure_android_jni_context(env);
    ffmpegkit_set_log_callback(ffmpegkit_jvm_log_callback);
    const int exit_code = ffmpegkit_execute((int)arg_count + 1, argv);
    ffmpegkit_set_log_callback(NULL);
    av_log_set_callback(av_log_default_callback);
    release_log_dispatch(env);

    for (jsize index = 0; index <= arg_count; ++index) {
        free(argv[(size_t)index]);
    }
    free(argv);
    return exit_code;
}
