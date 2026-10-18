#ifndef MEDIAMP_HANDLE_REGISTRY_H
#define MEDIAMP_HANDLE_REGISTRY_H

#include <jni.h>
#include <memory>

namespace mediampv {

class mpv_handle_t;

// Kotlin refers to a native player by an opaque id, never by its address. Every JNI
// entry point resolves the id here and holds the returned shared_ptr for the duration of
// the call, so a call racing nFinalize, or arriving after it (a stale id captured by a
// render consumer), finds nothing instead of freed memory. Ids are never reused, so a
// stale id cannot reach a newer player allocated at the same address either.

// Takes ownership; returns the id (> 0).
jlong register_handle(std::unique_ptr<mpv_handle_t> handle);
// The live instance for id, or null when it was never registered or already finalized.
std::shared_ptr<mpv_handle_t> find_handle(jlong id);
// Drops the registry's reference. The instance is destroyed once the last in-flight
// call returns (see release in handle_registry.cpp for calls on its own threads).
void unregister_handle(jlong id);

// The id to report in logs for an instance address (native code logs `this`), so native
// and Kotlin log lines of one player carry the same handle. 0 for null; the address
// itself for pointers that are not a registered player.
jlong log_id_for(const void *instance);
// Called at the end of ~mpv_handle_t: teardown logs still map to the id until then.
void forget_log_id(const void *instance);

} // namespace mediampv

#endif // MEDIAMP_HANDLE_REGISTRY_H
