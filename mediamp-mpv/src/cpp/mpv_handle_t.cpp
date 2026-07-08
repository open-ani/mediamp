#include <iostream>
#include <atomic>
#include <limits>
#include <new>
#include <string>
#include <mpv/render_gl.h>
#include "mpv_handle_t.h"
#include "method_cache.h"
#include "compatible_thread.h"
#include "global_lock.h"

#ifdef _WIN32
#include <windows.h>
#endif

extern "C" {
#include <libavcodec/jni.h>
}

#define CHECK_HANDLE() if (!handle_) { \
    LOG("mpv handle is not created when %s", __FUNCTION__); \
    return false; \
}
#define CHECK_HANDLE_RETURN_INT() if (!handle_) { \
    LOG("mpv handle is not created when %s", __FUNCTION__); \
    return 0; \
}

namespace mediampv {

CREATE_LOCK(global_guard);
JavaVM *global_jvm = nullptr;

namespace {

constexpr const char *kSeekableInputProtocol = "mediamp";

struct stream_lock_guard final {
#if defined(_WIN32) || defined(_WIN64)
    explicit stream_lock_guard(CompatibleLock &lock) : guard(lock) {}
    LockGuard guard;
#else
    explicit stream_lock_guard(std::recursive_mutex &lock) : guard(lock) {}
    std::lock_guard<std::recursive_mutex> guard;
#endif
};

struct attached_jni_env final {
    explicit attached_jni_env(JavaVM *vm) : vm(vm) {
        if (!vm) {
            return;
        }
        const jint get_env_result = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        if (get_env_result == JNI_OK) {
            return;
        }
        if (get_env_result == JNI_EDETACHED) {
#if defined(__ANDROID__)
            if (vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            }
#else
            if (vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) == JNI_OK) {
                attached = true;
            }
#endif
        }
    }

    ~attached_jni_env() {
        if (attached && vm) {
            vm->DetachCurrentThread();
        }
    }

    JNIEnv *env = nullptr;

private:
    JavaVM *vm = nullptr;
    bool attached = false;
};

bool clear_jni_exception(JNIEnv *env, const char *context) {
    if (!env || !env->ExceptionCheck()) {
        return false;
    }

    LOG("JNI exception in %s\n", context);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

void delete_global_ref(JNIEnv *env, jobject &reference) {
    if (env && reference) {
        env->DeleteGlobalRef(reference);
    }
    reference = nullptr;
}

} // namespace

struct mpv_handle_t::seekable_stream_entry final {
    seekable_stream_entry(JavaVM *vm, jobject input, std::string stream_uri, int64_t stream_size)
            : jvm(vm), input(input), uri(std::move(stream_uri)), size(stream_size) {}

    void request_cancel() {
        cancel_requested.store(true, std::memory_order_relaxed);
    }

    bool close_and_release() {
        if (released.exchange(true, std::memory_order_acq_rel)) {
            return false;
        }

        attached_jni_env attached_env(jvm);
        JNIEnv *env = attached_env.env;
        if (!env) {
            input = nullptr;
            return false;
        }

        stream_lock_guard guard(io_lock);
        if (input) {
            env->CallVoidMethod(input, mediampv::jni_mediamp_method_SeekableInput_close);
            clear_jni_exception(env, "SeekableInput.close");
            env->DeleteGlobalRef(input);
            input = nullptr;
        }

        return true;
    }

    JavaVM *jvm = nullptr;
    jobject input = nullptr;
    std::string uri;
    int64_t size = -1;
    std::atomic_bool opened{false};
    std::atomic_bool cancel_requested{false};
    std::atomic_bool released{false};
    CREATE_LOCK(io_lock);
};

struct mpv_handle_t::seekable_stream_cookie final {
    explicit seekable_stream_cookie(std::shared_ptr<seekable_stream_entry> entry)
            : entry(std::move(entry)) {}

    std::shared_ptr<seekable_stream_entry> entry;
    jobject read_buffer = nullptr;
    jint read_buffer_capacity = 0;
};

namespace {

bool ensure_seekable_read_buffer(JNIEnv *env, mpv_handle_t::seekable_stream_cookie *cookie, jint size) {
    if (size <= 0) {
        return true;
    }
    if (cookie->read_buffer && cookie->read_buffer_capacity >= size) {
        return true;
    }

    if (cookie->read_buffer) {
        env->DeleteGlobalRef(cookie->read_buffer);
        cookie->read_buffer = nullptr;
        cookie->read_buffer_capacity = 0;
    }

    jbyteArray local_buffer = env->NewByteArray(size);
    if (!local_buffer || clear_jni_exception(env, "NewByteArray")) {
        return false;
    }

    cookie->read_buffer = env->NewGlobalRef(local_buffer);
    env->DeleteLocalRef(local_buffer);
    cookie->read_buffer_capacity = cookie->read_buffer ? size : 0;
    return cookie->read_buffer != nullptr;
}

int64_t seekable_stream_read(void *cookie_ptr, char *buf, uint64_t nbytes) {
    auto *cookie = static_cast<mpv_handle_t::seekable_stream_cookie *>(cookie_ptr);
    if (!cookie || !cookie->entry) {
        return -1;
    }

    auto entry = cookie->entry;
    if (entry->cancel_requested.load(std::memory_order_relaxed) || entry->released.load(std::memory_order_acquire)) {
        return -1;
    }

    attached_jni_env attached_env(entry->jvm);
    JNIEnv *env = attached_env.env;
    if (!env) {
        return -1;
    }

    const jint requested_size = static_cast<jint>(
            std::min<uint64_t>(nbytes, static_cast<uint64_t>(std::numeric_limits<jint>::max())));
    if (requested_size <= 0) {
        return 0;
    }

    stream_lock_guard guard(entry->io_lock);
    if (entry->released.load(std::memory_order_acquire) || !entry->input) {
        return -1;
    }
    if (!ensure_seekable_read_buffer(env, cookie, requested_size)) {
        return -1;
    }

    auto read_buffer = reinterpret_cast<jbyteArray>(cookie->read_buffer);
    const jint bytes_read = env->CallIntMethod(
            entry->input,
            mediampv::jni_mediamp_method_SeekableInput_read,
            read_buffer,
            0,
            requested_size
    );
    if (clear_jni_exception(env, "SeekableInput.read")) {
        return -1;
    }

    if (bytes_read > 0) {
        env->GetByteArrayRegion(read_buffer, 0, bytes_read, reinterpret_cast<jbyte *>(buf));
        if (clear_jni_exception(env, "GetByteArrayRegion")) {
            return -1;
        }
    }

    return bytes_read;
}

int64_t seekable_stream_seek(void *cookie_ptr, int64_t offset) {
    auto *cookie = static_cast<mpv_handle_t::seekable_stream_cookie *>(cookie_ptr);
    if (!cookie || !cookie->entry) {
        return MPV_ERROR_GENERIC;
    }

    auto entry = cookie->entry;
    if (offset < 0 || entry->cancel_requested.load(std::memory_order_relaxed) ||
        entry->released.load(std::memory_order_acquire)) {
        return MPV_ERROR_GENERIC;
    }

    attached_jni_env attached_env(entry->jvm);
    JNIEnv *env = attached_env.env;
    if (!env) {
        return MPV_ERROR_GENERIC;
    }

    stream_lock_guard guard(entry->io_lock);
    if (entry->released.load(std::memory_order_acquire) || !entry->input) {
        return MPV_ERROR_GENERIC;
    }

    env->CallVoidMethod(entry->input, mediampv::jni_mediamp_method_SeekableInput_seekTo, static_cast<jlong>(offset));
    if (clear_jni_exception(env, "SeekableInput.seekTo")) {
        return MPV_ERROR_GENERIC;
    }

    return offset;
}

int64_t seekable_stream_size(void *cookie_ptr) {
    auto *cookie = static_cast<mpv_handle_t::seekable_stream_cookie *>(cookie_ptr);
    if (!cookie || !cookie->entry) {
        return MPV_ERROR_UNSUPPORTED;
    }

    auto entry = cookie->entry;
    if (entry->size < 0) {
        return MPV_ERROR_UNSUPPORTED;
    }

    return entry->size;
}

void seekable_stream_close(void *cookie_ptr) {
    auto *cookie = static_cast<mpv_handle_t::seekable_stream_cookie *>(cookie_ptr);
    if (!cookie) {
        return;
    }

    attached_jni_env attached_env(cookie->entry ? cookie->entry->jvm : nullptr);
    if (attached_env.env && cookie->read_buffer) {
        attached_env.env->DeleteGlobalRef(cookie->read_buffer);
    }

    if (cookie->entry) {
        cookie->entry->close_and_release();
    }

    delete cookie;
}

void seekable_stream_cancel(void *cookie_ptr) {
    auto *cookie = static_cast<mpv_handle_t::seekable_stream_cookie *>(cookie_ptr);
    if (!cookie || !cookie->entry) {
        return;
    }

    cookie->entry->request_cancel();
}

} // namespace

void mpv_handle_t::create(JNIEnv *env, jobject app_context) {
    FP;
    if (!env) {
        LOG("JNI env is null when %s\n", __FUNCTION__);
        return;
    }

    LOCK(global_guard);

    if (!global_jvm) {
        if (env->GetJavaVM(&global_jvm) != JNI_OK || !global_jvm) {
            LOG("failed to get current jvm");
            return;
        }

        av_jni_set_java_vm(global_jvm, &app_context);
    }

    jvm_ = global_jvm;
    jni_cache_classes(env);
    event_loop_request_exit.store(false, std::memory_order_release);
    handle_ = mpv_create();
    if (!handle_) {
        LOG("failed to create mpv handle");
        return;
    }

    // use terminal log level but request verbose messages
    // this way --msg-level can be used to adjust later
    mpv_request_log_messages(handle_, "terminal-default");
    mpv_set_option_string(handle_, "msg-level", "all=v");
}

mpv_handle_t::~mpv_handle_t() {
    destroy(nullptr);
}

bool mpv_handle_t::initialize() {
    FP;

    if (!handle_) return false;
    if (event_thread_) return true;
    if (mpv_initialize(handle_) < 0) {
        LOG("failed to initialize mpv");
        return false;
    }

    event_loop_request_exit.store(false, std::memory_order_release);
    event_thread_ = std::make_shared<mediampv::compatible_thread>([this] { event_loop(nullptr); });
    if (!event_thread_->create()) {
        LOG("failed to create event thread");
        event_thread_.reset();
        return false;
    }

    return true;
}

void mpv_handle_t::on_render_update(void *context) {
    auto *instance = static_cast<mpv_handle_t *>(context);
    if (!instance) return;
#if defined(__APPLE__) || defined(_WIN32)
    // The render thread consumes the update and calls notify_render_update() only
    // after the frame is actually in a shared buffer, so consumers never wake up to
    // a stale buffer.
    instance->signal_render_update();
#else
    instance->notify_render_update();
#endif
}

bool mpv_handle_t::set_event_listener(JNIEnv *env, jobject listener) {
    FP;
    if (!env || !listener) {
        return false;
    }

    mediampv::jni_cache_classes(env);
    if (!mediampv::jni_mediamp_clazz_EventListener) {
        return false;
    }

    if (env->IsInstanceOf(listener, mediampv::jni_mediamp_clazz_EventListener) != JNI_TRUE) {
        LOG("listener is not an instance of EventListener");
        return false;
    }

    clear_event_listener(env);
    event_listener_ = env->NewGlobalRef(listener);
    if (!event_listener_ || clear_jni_exception(env, "NewGlobalRef(EventListener)")) {
        event_listener_ = nullptr;
        return false;
    }

    return true;
}

bool mpv_handle_t::set_render_update_listener(JNIEnv *env, jobject listener) {
    FP;
    if (!env) {
        return false;
    }

    mediampv::jni_cache_classes(env);
    if (listener && (!mediampv::jni_mediamp_clazz_RenderUpdateListener ||
        env->IsInstanceOf(listener, mediampv::jni_mediamp_clazz_RenderUpdateListener) != JNI_TRUE)) {
        LOG("listener is not an instance of RenderUpdateListener");
        return false;
    }

    LOCK(render_update_listener_lock);
    clear_render_update_listener(env);
    if (!listener) {
        return true;
    }

    render_update_listener_ = env->NewGlobalRef(listener);
    if (!render_update_listener_ || clear_jni_exception(env, "NewGlobalRef(RenderUpdateListener)")) {
        render_update_listener_ = nullptr;
        return false;
    }

    return true;
}

bool mpv_handle_t::command(const char **args) {
    FP;
    CHECK_HANDLE()
    if (!args) {
        return false;
    }
    return mpv_command(handle_, args) >= 0;
}

bool mpv_handle_t::set_option(const char *key, const char *value) {
    FP;
    CHECK_HANDLE()
    if (!key || !value) {
        return false;
    }
    return mpv_set_option_string(handle_, key, value) >= 0;
}

bool mpv_handle_t::get_property(const char *name, mpv_format format, void *out_result) {
    FP;
    CHECK_HANDLE()
    return mpv_get_property(handle_, name, format, out_result) >= 0;
}

bool mpv_handle_t::set_property(const char *name, mpv_format format, void *in_value) {
    FP;
    CHECK_HANDLE()
    return mpv_set_property(handle_, name, format, in_value) >= 0;
}

bool mpv_handle_t::observe_property(const char *property, mpv_format format, uint64_t reply_data) {
    FP;
    CHECK_HANDLE()
    return mpv_observe_property(handle_, reply_data, property, format) >= 0;
}

bool mpv_handle_t::unobserve_property(uint64_t reply_data) {
    FP;
    CHECK_HANDLE()
    return mpv_unobserve_property(handle_, reply_data) >= 0;
}

bool mpv_handle_t::ensure_stream_protocol_registered() {
    CHECK_HANDLE()
    if (stream_protocol_registered_) {
        return true;
    }

    const int result = mpv_stream_cb_add_ro(handle_, kSeekableInputProtocol, this, &mpv_handle_t::open_seekable_stream);
    if (result < 0) {
        LOG("failed to register mpv stream callback protocol: %d\n", result);
        return false;
    }

    stream_protocol_registered_ = true;
    return true;
}

bool mpv_handle_t::register_seekable_input(JNIEnv *env, jobject seekable_input, const char *uri, int64_t size) {
    FP;
    if (!handle_) {
        LOG("mpv handle is not created when %s", __FUNCTION__);
        return false;
    }
    if (!seekable_input) {
        LOG("seekable input is null when %s\n", __FUNCTION__);
        return false;
    }
    if (!jvm_) {
        LOG("JVM is not initialized when %s\n", __FUNCTION__);
        return false;
    }
    if (!uri || uri[0] == '\0') {
        LOG("seekable input uri is null or empty when %s\n", __FUNCTION__);
        return false;
    }

    jni_cache_classes(env);
    if (env->IsInstanceOf(seekable_input, mediampv::jni_mediamp_clazz_SeekableInput) != JNI_TRUE) {
        LOG("seekable input is not an instance of SeekableInput\n");
        return false;
    }

    LOCK(stream_registry_lock);
    if (!ensure_stream_protocol_registered()) {
        return false;
    }

    jobject global_input = env->NewGlobalRef(seekable_input);
    if (!global_input || clear_jni_exception(env, "NewGlobalRef(SeekableInput)")) {
        return false;
    }

    const std::string stream_uri(uri);
    if (seekable_streams_.find(stream_uri) != seekable_streams_.end()) {
        LOG("seekable input uri is already registered: %s\n", uri);
        env->DeleteGlobalRef(global_input);
        return false;
    }

    seekable_streams_.emplace(stream_uri, std::make_shared<seekable_stream_entry>(jvm_, global_input, stream_uri, size));
    return true;
}

bool mpv_handle_t::unregister_seekable_input(const char *uri) {
    FP;
    CHECK_HANDLE()
    if (!uri) {
        return false;
    }

    std::shared_ptr<seekable_stream_entry> entry;
    {
        LOCK(stream_registry_lock);
        auto iterator = seekable_streams_.find(uri);
        if (iterator == seekable_streams_.end()) {
            return false;
        }
        entry = iterator->second;
        seekable_streams_.erase(iterator);
    }

    entry->request_cancel();
    if (!entry->opened.load(std::memory_order_acquire)) {
        entry->close_and_release();
    }

    return true;
}

int mpv_handle_t::open_seekable_stream(const char *uri, mpv_stream_cb_info *info) {
    if (!uri || !info) {
        return MPV_ERROR_LOADING_FAILED;
    }

    std::shared_ptr<seekable_stream_entry> entry;
    {
        LOCK(stream_registry_lock);
        auto iterator = seekable_streams_.find(uri);
        if (iterator == seekable_streams_.end()) {
            LOG("no registered seekable stream for uri %s\n", uri);
            return MPV_ERROR_LOADING_FAILED;
        }
        entry = iterator->second;
    }

    if (entry->released.load(std::memory_order_acquire)) {
        return MPV_ERROR_LOADING_FAILED;
    }
    if (entry->opened.exchange(true, std::memory_order_acq_rel)) {
        LOG("seekable stream %s has already been opened\n", uri);
        return MPV_ERROR_LOADING_FAILED;
    }

    auto *cookie = new (std::nothrow) seekable_stream_cookie(entry);
    if (!cookie) {
        entry->opened.store(false, std::memory_order_release);
        return MPV_ERROR_NOMEM;
    }

    entry->cancel_requested.store(false, std::memory_order_relaxed);
    info->cookie = cookie;
    info->read_fn = &seekable_stream_read;
    info->seek_fn = &seekable_stream_seek;
    info->size_fn = &seekable_stream_size;
    info->close_fn = &seekable_stream_close;
    info->cancel_fn = &seekable_stream_cancel;
    return 0;
}

int mpv_handle_t::open_seekable_stream(void *user_data, char *uri, mpv_stream_cb_info *info) {
    auto *instance = static_cast<mpv_handle_t *>(user_data);
    return instance ? instance->open_seekable_stream(uri, info) : MPV_ERROR_LOADING_FAILED;
}

CREATE_LOCK(surface_access_lock);

bool mpv_handle_t::attach_android_surface(JNIEnv *env, jobject surface) {
    FP;
    LOCK(surface_access_lock);
    CHECK_HANDLE()

#ifdef __ANDROID__
    if (!env || !surface) {
        return false;
    }
    jni_cache_classes(env);
    if (!jni_mediamp_clazz_android_Surface) {
        return false;
    }
    if (surface_attached_ || surface_) clear_android_surface(env);
    if (env->IsInstanceOf(surface, mediampv::jni_mediamp_clazz_android_Surface) != JNI_TRUE) {
        LOG("surface is not instance of android.view.Surface");
        return false;
    }

    jobject ref = env->NewGlobalRef(surface);
    if (!ref || clear_jni_exception(env, "NewGlobalRef(Surface)")) {
        return false;
    }
    int64_t wid = (int64_t)(intptr_t) ref;
    surface_attached_ = mpv_set_option(handle_, "wid", MPV_FORMAT_INT64, &wid) >= 0;
    if (!surface_attached_) {
        env->DeleteGlobalRef(ref);
        return false;
    }

    surface_ = ref;
    return surface_attached_;
#else
    LOG("attach_android_surface is only implemented on Android");
    return false;
#endif
}

bool mpv_handle_t::detach_android_surface(JNIEnv *env) {
    FP;
    LOCK(surface_access_lock);
    CHECK_HANDLE()

#ifdef __ANDROID__
    if (!surface_) return false;

    int64_t wid = 0;
    bool result = mpv_set_option(handle_, "wid", MPV_FORMAT_INT64, &wid) >= 0;
    clear_android_surface(env);

    return result;
#else
    LOG("detach_android_surface is only implemented on Android");
    return false;
#endif
}

#ifdef __ANDROID__
bool mpv_handle_t::attach_window_surface(int64_t wid) {
    FP;
    CHECK_HANDLE();
    return mpv_set_option(handle_, "wid", MPV_FORMAT_INT64, &wid) >= 0;
}

bool mpv_handle_t::detach_window_surface() {
    FP;
    CHECK_HANDLE();
    int64_t wid = 0;
    return mpv_set_option(handle_, "wid", MPV_FORMAT_INT64, &wid) >= 0;
}
#endif

bool mpv_handle_t::destroy(JNIEnv *env) {
    FP;
    event_loop_request_exit.store(true, std::memory_order_release);
    if (handle_) {
        mpv_wakeup(handle_);
    }

    if (event_thread_) {
        event_thread_->join();
        event_thread_.reset();
    }

    attached_jni_env attached_env(env ? nullptr : jvm_);
    JNIEnv *cleanup_env = env ? env : attached_env.env;
    clear_event_listener(cleanup_env);
    clear_render_update_listener(cleanup_env);
#ifdef __ANDROID__
    clear_android_surface(cleanup_env);
#endif
#if defined(_WIN32) || defined(__APPLE__)
    cleanup_render_resources();
#endif
    clear_seekable_streams();

    if (handle_) {
        mpv_terminate_destroy(handle_);
        handle_ = nullptr;
    }
    stream_protocol_registered_ = false;

    return true;
}

void mpv_handle_t::clear_event_listener(JNIEnv *env) {
    attached_jni_env attached_env(env ? nullptr : jvm_);
    delete_global_ref(env ? env : attached_env.env, event_listener_);
}

void mpv_handle_t::clear_render_update_listener(JNIEnv *env) {
    attached_jni_env attached_env(env ? nullptr : jvm_);
    delete_global_ref(env ? env : attached_env.env, render_update_listener_);
}

void mpv_handle_t::notify_render_update() {
    if (!jvm_) {
        return;
    }

    attached_jni_env attached_env(jvm_);
    JNIEnv *env = attached_env.env;
    if (!env || !mediampv::jni_mediamp_method_RenderUpdateListener_onRenderUpdate) {
        return;
    }

    LOCK(render_update_listener_lock);
    if (!render_update_listener_) {
        return;
    }

    env->CallVoidMethod(render_update_listener_, mediampv::jni_mediamp_method_RenderUpdateListener_onRenderUpdate);
    clear_jni_exception(env, "RenderUpdateListener.onRenderUpdate");
}

void mpv_handle_t::clear_seekable_streams() {
    std::unordered_map<std::string, std::shared_ptr<seekable_stream_entry>> remaining_streams;
    {
        LOCK(stream_registry_lock);
        remaining_streams.swap(seekable_streams_);
    }
    for (auto &entry : remaining_streams) {
        entry.second->request_cancel();
        entry.second->close_and_release();
    }
}

#ifdef __ANDROID__
void mpv_handle_t::clear_android_surface(JNIEnv *env) {
    attached_jni_env attached_env(env ? nullptr : jvm_);
    delete_global_ref(env ? env : attached_env.env, surface_);
    surface_attached_ = false;
}
#endif

} // namespace mediampv
