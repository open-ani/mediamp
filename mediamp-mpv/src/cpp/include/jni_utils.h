#ifndef MEDIAMP_JNI_UTILS_H
#define MEDIAMP_JNI_UTILS_H

#include <jni.h>

namespace mediampv {

// Provides a JNIEnv for the calling thread: attaches it to the JVM when needed and
// detaches on scope exit only if this helper performed the attach, so a thread the JVM
// already owns (event loop, render thread, a thread inside a JNI downcall) is never
// wrongly detached. env is null when vm is null or the attach failed.
class scoped_jni_env final {
public:
    explicit scoped_jni_env(JavaVM *vm);
    ~scoped_jni_env();

    scoped_jni_env(const scoped_jni_env &) = delete;
    scoped_jni_env &operator=(const scoped_jni_env &) = delete;

    JNIEnv *env = nullptr;

private:
    JavaVM *vm_ = nullptr;
    bool attached_ = false;
};

// Describes and clears a pending Java exception, then logs `context`. Returns whether
// one was pending. Clears before logging: the log path makes JNI calls, which must not
// run with an exception pending.
bool clear_jni_exception(JNIEnv *env, const void *instance_handle, const char *context);

// Deletes a global reference (if any) and nulls it.
template <typename T>
void delete_global_ref(JNIEnv *env, T &reference) {
    if (env && reference) {
        env->DeleteGlobalRef(reference);
    }
    reference = nullptr;
}

} // namespace mediampv

#endif // MEDIAMP_JNI_UTILS_H
