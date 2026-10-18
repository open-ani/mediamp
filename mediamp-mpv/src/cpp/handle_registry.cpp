#include "handle_registry.h"

#include <cstdint>
#include <mutex>
#include <shared_mutex>
#include <thread>
#include <unordered_map>

#include "mpv_handle_t.h"

namespace mediampv {

namespace {

struct registry_state {
    std::shared_mutex mutex;
    std::unordered_map<jlong, std::shared_ptr<mpv_handle_t>> handles_by_id;
    std::unordered_map<const void *, jlong> ids_by_address;
    jlong next_id = 1;
};

// Leaked on purpose, never destroyed. A player still registered at process exit (the
// JVM may exit before an asynchronous close() reached nFinalize) must not be torn down
// by static destructors: on Windows they run under the loader lock, where joining the
// player's threads deadlocks and the process never finishes exiting. That hung
// MpvZeroConfigTest (a short-lived JVM) on Windows and macOS.
registry_state &state() {
    static registry_state *const instance = new registry_state();
    return *instance;
}

void release(mpv_handle_t *instance) {
    if (instance->is_current_thread_owned()) {
        // The last reference was dropped by a call made on one of the instance's own
        // threads (e.g. a Kotlin event callback calling back into native code while
        // nFinalize ran elsewhere). Destroying here would join that thread from itself,
        // so hand the teardown to a thread of its own.
        std::thread([instance] { delete instance; }).detach();
        return;
    }
    delete instance;
}

} // namespace

jlong register_handle(std::unique_ptr<mpv_handle_t> handle) {
    std::shared_ptr<mpv_handle_t> shared(handle.release(), &release);
    std::unique_lock<std::shared_mutex> lock(state().mutex);
    const jlong id = state().next_id++;
    state().ids_by_address[shared.get()] = id;
    state().handles_by_id.emplace(id, std::move(shared));
    return id;
}

std::shared_ptr<mpv_handle_t> find_handle(jlong id) {
    std::shared_lock<std::shared_mutex> lock(state().mutex);
    const auto it = state().handles_by_id.find(id);
    return it == state().handles_by_id.end() ? nullptr : it->second;
}

void unregister_handle(jlong id) {
    std::shared_ptr<mpv_handle_t> released;
    {
        std::unique_lock<std::shared_mutex> lock(state().mutex);
        const auto it = state().handles_by_id.find(id);
        if (it == state().handles_by_id.end()) return;
        released = std::move(it->second);
        state().handles_by_id.erase(it);
    }
    // `released` may be the last reference: destroy outside the registry lock, since
    // teardown logs (log_id_for) take it again.
}

jlong log_id_for(const void *instance) {
    if (!instance) return 0;
    std::shared_lock<std::shared_mutex> lock(state().mutex);
    const auto it = state().ids_by_address.find(instance);
    return it != state().ids_by_address.end()
        ? it->second
        : static_cast<jlong>(reinterpret_cast<std::uintptr_t>(instance));
}

void forget_log_id(const void *instance) {
    std::unique_lock<std::shared_mutex> lock(state().mutex);
    state().ids_by_address.erase(instance);
}

} // namespace mediampv
