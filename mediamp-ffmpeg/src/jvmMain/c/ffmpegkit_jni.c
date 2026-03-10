#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#if defined(_WIN32)
#include <io.h>
#define dup _dup
#define dup2 _dup2
#define close _close
#define fileno _fileno
#define open _open
#define O_CLOEXEC 0
#else
#include <unistd.h>
#include <dlfcn.h>
#endif

int ffmpegkit_execute(int argc, char **argv);

typedef int (*ffmpeg_av_jni_set_java_vm_fn)(void *vm, void *log_ctx);
typedef int (*ffmpeg_av_jni_set_android_app_ctx_fn)(void *app_ctx, void *log_ctx);

static JavaVM *g_java_vm = NULL;
static jobject g_android_app_context = NULL;

static void throw_runtime_exception(JNIEnv *env, const char *message) {
    jclass runtime_exception = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (runtime_exception != NULL) {
        (*env)->ThrowNew(env, runtime_exception, message);
    }
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

JNIEXPORT void JNICALL Java_org_openani_mediamp_ffmpeg_JvmFFmpegProcess_initializeAndroidContext(
    JNIEnv *env,
    jclass clazz,
    jobject app_context
);

JNIEXPORT jint JNICALL Java_org_openani_mediamp_ffmpeg_JvmFFmpegProcess_executeNative(
    JNIEnv *env,
    jclass clazz,
    jobjectArray args,
    jstring stdout_path,
    jstring stderr_path
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
    jobjectArray args,
    jstring stdout_path,
    jstring stderr_path
) {
    (void)clazz;
    if (stdout_path == NULL || stderr_path == NULL) {
        throw_runtime_exception(env, "stdoutPath and stderrPath are required.");
        return -1;
    }

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

    const char *stdout_chars = (*env)->GetStringUTFChars(env, stdout_path, NULL);
    const char *stderr_chars = (*env)->GetStringUTFChars(env, stderr_path, NULL);
    if (stdout_chars == NULL || stderr_chars == NULL) {
        if (stdout_chars != NULL) {
            (*env)->ReleaseStringUTFChars(env, stdout_path, stdout_chars);
        }
        if (stderr_chars != NULL) {
            (*env)->ReleaseStringUTFChars(env, stderr_path, stderr_chars);
        }
        for (jsize index = 0; index <= arg_count; ++index) {
            free(argv[(size_t)index]);
        }
        free(argv);
        throw_runtime_exception(env, "Failed to resolve FFmpeg output paths.");
        return -1;
    }

    int stdout_fd = open(stdout_chars, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0666);
    int stderr_fd = open(stderr_chars, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0666);
    (*env)->ReleaseStringUTFChars(env, stdout_path, stdout_chars);
    (*env)->ReleaseStringUTFChars(env, stderr_path, stderr_chars);
    if (stdout_fd < 0 || stderr_fd < 0) {
        if (stdout_fd >= 0) close(stdout_fd);
        if (stderr_fd >= 0) close(stderr_fd);
        for (jsize index = 0; index <= arg_count; ++index) {
            free(argv[(size_t)index]);
        }
        free(argv);
        throw_runtime_exception(env, strerror(errno));
        return -1;
    }

    fflush(stdout);
    fflush(stderr);
    int saved_stdout = dup(fileno(stdout));
    int saved_stderr = dup(fileno(stderr));
    if (saved_stdout < 0 || saved_stderr < 0) {
        if (saved_stdout >= 0) close(saved_stdout);
        if (saved_stderr >= 0) close(saved_stderr);
        close(stdout_fd);
        close(stderr_fd);
        for (jsize index = 0; index <= arg_count; ++index) {
            free(argv[(size_t)index]);
        }
        free(argv);
        throw_runtime_exception(env, "Failed to duplicate stdout/stderr.");
        return -1;
    }

    if (dup2(stdout_fd, fileno(stdout)) < 0 || dup2(stderr_fd, fileno(stderr)) < 0) {
        close(saved_stdout);
        close(saved_stderr);
        close(stdout_fd);
        close(stderr_fd);
        for (jsize index = 0; index <= arg_count; ++index) {
            free(argv[(size_t)index]);
        }
        free(argv);
        throw_runtime_exception(env, "Failed to redirect stdout/stderr.");
        return -1;
    }

    close(stdout_fd);
    close(stderr_fd);

    configure_android_jni_context(env);
    const int exit_code = ffmpegkit_execute((int)arg_count + 1, argv);

    fflush(stdout);
    fflush(stderr);
    dup2(saved_stdout, fileno(stdout));
    dup2(saved_stderr, fileno(stderr));
    close(saved_stdout);
    close(saved_stderr);

    for (jsize index = 0; index <= arg_count; ++index) {
        free(argv[(size_t)index]);
    }
    free(argv);
    return exit_code;
}
