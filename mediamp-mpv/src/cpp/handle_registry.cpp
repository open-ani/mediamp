#include "handle_registry.h"

#include <cstdint>
#include <mutex>
#include <shared_mutex>
#include <thread>
#include <unordered_map>

#include "mpv_handle_t.h"

namespace mediampv {

namespace {

std::shared_mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<mpv_handle_t>> handles_by_id;
std::unordered_map<const void *, jlong> ids_by_address;
jlong next_id = 1;

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
    std::unique_lock<std::shared_mutex> lock(registry_mutex);
    const jlong id = next_id++;
    ids_by_address[shared.get()] = id;
    handles_by_id.emplace(id, std::move(shared));
    return id;
}

std::shared_ptr<mpv_handle_t> find_handle(jlong id) {
    std::shared_lock<std::shared_mutex> lock(registry_mutex);
    const auto it = handles_by_id.find(id);
    return it == handles_by_id.end() ? nullptr : it->second;
}

void unregister_handle(jlong id) {
    std::shared_ptr<mpv_handle_t> released;
    {
        std::unique_lock<std::shared_mutex> lock(registry_mutex);
        const auto it = handles_by_id.find(id);
        if (it == handles_by_id.end()) return;
        released = std::move(it->second);
        handles_by_id.erase(it);
    }
    // `released` may be the last reference: destroy outside the registry lock, since
    // teardown logs (log_id_for) take it again.
}

jlong log_id_for(const void *instance) {
    if (!instance) return 0;
    std::shared_lock<std::shared_mutex> lock(registry_mutex);
    const auto it = ids_by_address.find(instance);
    return it != ids_by_address.end()
        ? it->second
        : static_cast<jlong>(reinterpret_cast<std::uintptr_t>(instance));
}

void forget_log_id(const void *instance) {
    std::unique_lock<std::shared_mutex> lock(registry_mutex);
    ids_by_address.erase(instance);
}

} // namespace mediampv
