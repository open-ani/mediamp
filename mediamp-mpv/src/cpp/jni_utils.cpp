#include "jni_utils.h"

#include "log.h"

namespace mediampv {

scoped_jni_env::scoped_jni_env(JavaVM *vm) : vm_(vm) {
    if (!vm_) {
        return;
    }
    const jint rc = vm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (rc == JNI_OK) {
        return;
    }
    env = nullptr;
    if (rc == JNI_EDETACHED) {
#if defined(__ANDROID__)
        if (vm_->AttachCurrentThread(&env, nullptr) == JNI_OK) {
#else
        if (vm_->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) == JNI_OK) {
#endif
            attached_ = true;
        } else {
            env = nullptr;
        }
    }
}

scoped_jni_env::~scoped_jni_env() {
    if (attached_ && vm_) {
        vm_->DetachCurrentThread();
    }
}

bool clear_jni_exception(JNIEnv *env, const void *instance_handle, const char *context) {
    if (!env || !env->ExceptionCheck()) {
        return false;
    }
    env->ExceptionDescribe();
    env->ExceptionClear();
    LOG(instance_handle, LOG_LEVEL_ERROR, "JNI exception in %s", context);
    return true;
}

} // namespace mediampv
