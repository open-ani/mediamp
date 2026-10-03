#ifndef MEDIAMP_SURFACE_RING_H
#define MEDIAMP_SURFACE_RING_H

#include "desktop_renderer.h"

#ifdef MEDIAMPV_DESKTOP

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <thread>

#include <jni.h>
#include <mpv/render.h>

#include "jni_utils.h"
#include "log.h"
#include "mpv_handle_t.h"

namespace mediampv {

// The producer protocol shared by the GPU render paths (D3D11 shared textures, Metal
// IOSurfaces, GLX shared textures). A dedicated render thread drives mpv's render API
// into a triple-buffered ring of textures that the consumer (Skia) samples directly;
// consumers never render, they read the packed frame state and wrap the latest buffer.
//
// Threading: the render thread is the only thread that renders or mutates the ring.
// mpv's update callback, reconfiguration (resize), consumer acks and path-specific
// requests are posted under mutex_ and wake the thread. A resize retires the current
// ring instead of freeing it: the consumer may still be sampling it until it re-wrapped
// the new generation and acked (ack_retired_buffers). Only one retired generation is
// kept, so a further resize waits for that ack. CPU readback consumers (D3D11 only)
// never hold ring textures, so there a resize frees the old ring immediately.
//
// Why 3 buffers: the render thread writes (latest+1)%3 while Skia may still have
// sampling of both the published latest and the previous frame in flight on its own
// GPU timeline.
//
// Subclasses provide the platform hooks below and call start_render_thread() from their
// factory; their destructor must call shutdown() (hooks are virtual).
template <typename Buffer>
class surface_ring : public desktop_renderer {
public:
    static constexpr int kBufferCount = 3;

    surface_ring(const surface_ring &) = delete;
    surface_ring &operator=(const surface_ring &) = delete;

    void shutdown() final {
        stop_render_thread();
        after_shutdown();
    }

    bool set_surface_config(int width, int height, int64_t consumer_device) override {
        return request_config(width, height, consumer_device, false);
    }

    uint64_t frame_state() final {
        return frame_state_.load(std::memory_order_acquire);
    }

    bool has_surface() final {
        std::lock_guard<std::mutex> guard(mutex_);
        return buffers_allocated_;
    }

    int64_t buffer_texture(int index) final {
        std::lock_guard<std::mutex> guard(mutex_);
        if (!buffers_allocated_ || index < 0 || index >= kBufferCount) return 0;
        return texture_handle(buffers_[index]);
    }

    bool ack_retired_buffers() final {
        {
            std::lock_guard<std::mutex> guard(mutex_);
            if (!thread_.joinable()) return false;
            retire_ack_pending_ = true;
        }
        cv_.notify_all();
        return true;
    }

    bool render_frame_pixels(int width, int height, std::vector<uint32_t> &pixels) final {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!thread_.joinable() || width <= 0 || height <= 0 || frame_request_pending_) return false;
        frame_request_pending_ = true;
        frame_request_finished_ = false;
        frame_request_width_ = width;
        frame_request_height_ = height;
        ++frame_request_serial_;
        cv_.notify_all();
        // Bounded: a wedged GPU must not hang the calling (JNI) thread. A result that
        // arrives after this gave up is dropped by the render thread (serial mismatch).
        const bool served = cv_.wait_for(
            lock, std::chrono::seconds(5), [this] { return frame_request_finished_ || quit_; });
        if (!served || !frame_request_finished_) {
            frame_request_pending_ = false;
            return false;
        }
        frame_request_finished_ = false;
        if (!frame_request_ok_) return false;
        pixels = std::move(frame_request_pixels_);
        return true;
    }

protected:
    surface_ring(mpv_handle_t &owner, const char *name) : owner_(owner), name_(name) {}

    // ---- platform hooks ----

    // Render thread, before the loop: e.g. make a GL context current, create the mpv
    // render context. false aborts start_render_thread().
    virtual bool on_thread_start() { return true; }
    // Render thread, after the loop and after both rings were released.
    virtual void on_thread_exit() {}
    // Calling thread of shutdown(), after the render thread has exited.
    virtual void after_shutdown() {}

    // Before a ring allocation. consumer_device changed since the last allocation (or
    // this is the first); prepare what allocate_buffer needs. false fails the allocation.
    virtual bool prepare_device_locked(int64_t consumer_device, bool changed) { return true; }
    // Surface deactivated: drop consumer-device state.
    virtual void release_device_locked() {}
    virtual bool allocate_buffer(Buffer &buffer, int width, int height) = 0;
    virtual void destroy_buffer(Buffer &buffer) = 0;
    // The consumer-visible texture handle of a buffer (buffer_texture()).
    virtual int64_t texture_handle(const Buffer &buffer) const = 0;
    // Renders the current mpv frame into buffer and waits until it is complete on the
    // GPU (consumers sample it right after publication). Unlocked, render thread.
    virtual bool render_into(const Buffer &buffer) = 0;
    // Renders the current mpv frame into a temporary width x height target of the path's
    // own kind and reads it back as ARGB_8888 (top-down, alpha opaque); the ring is not
    // touched. Unlocked, render thread.
    virtual bool render_frame_pixels_on_render_thread(int width, int height, std::vector<uint32_t> &pixels) = 0;

    // CPU readback (only D3D11 supports it): set up after a ring allocation, copy a
    // rendered buffer into system memory (unlocked), publish that copy (locked), release.
    virtual bool setup_readback_locked() { return false; }
    virtual bool read_back(const Buffer &buffer) { return false; }
    virtual void publish_readback_locked() {}
    virtual void release_readback_locked() {}

    // Path-specific requests served on the render thread (GLX screenshots/readbacks,
    // which need its GL context). serve_requests_locked returns whether it served one;
    // the loop then waits again. It may unlock `lock` temporarily.
    virtual bool has_requests_locked() const { return false; }
    virtual bool serve_requests_locked(std::unique_lock<std::mutex> &lock) { return false; }

    // ---- for subclasses ----

    bool request_config(int width, int height, int64_t consumer_device, bool cpu_readback) {
        {
            std::lock_guard<std::mutex> guard(mutex_);
            if (!thread_.joinable()) return false;
            pending_width_ = width;
            pending_height_ = height;
            pending_device_ = consumer_device;
            pending_cpu_readback_ = cpu_readback;
            config_pending_ = true;  // the newest request replaces an unprocessed one
        }
        cv_.notify_all();
        return true;
    }

    // Starts the render thread and waits for on_thread_start(); false (thread already
    // joined) when that failed.
    bool start_render_thread() {
        {
            std::lock_guard<std::mutex> guard(mutex_);
            quit_ = false;
            thread_started_ = thread_start_ok_ = false;
        }
        thread_ = std::thread([this] { thread_loop(); });
        std::unique_lock<std::mutex> lock(mutex_);
        cv_.wait(lock, [this] { return thread_started_; });
        const bool ok = thread_start_ok_;
        lock.unlock();
        if (!ok) stop_render_thread();
        return ok;
    }

    void stop_render_thread() {
        {
            std::lock_guard<std::mutex> guard(mutex_);
            if (!thread_.joinable()) return;
            quit_ = true;
        }
        cv_.notify_all();
        thread_.join();
    }

    // Routes mpv's update callback to this ring; clear it before freeing the context.
    void attach_update_callback() {
        mpv_render_context_set_update_callback(render_context_, &surface_ring::on_mpv_update, this);
    }
    void detach_update_callback() {
        if (render_context_) mpv_render_context_set_update_callback(render_context_, nullptr, nullptr);
    }

    mpv_handle_t &owner_;
    const char *const name_;
    // Created by the subclass (on the calling thread or in on_thread_start).
    mpv_render_context *render_context_ = nullptr;

    // Ring state: guarded by mutex_, mutated only on the render thread (so the render
    // thread may read it unlocked).
    std::mutex mutex_;
    std::condition_variable cv_;
    Buffer buffers_[kBufferCount];
    Buffer retired_buffers_[kBufferCount];
    bool has_retired_buffers_ = false;
    bool buffers_allocated_ = false;
    bool cpu_readback_ = false;
    int buffer_width_ = 0, buffer_height_ = 0;
    int latest_index_ = -1;
    bool quit_ = false;

private:
    static void on_mpv_update(void *context) {
        auto *ring = static_cast<surface_ring *>(context);
        {
            std::lock_guard<std::mutex> guard(ring->mutex_);
            ring->render_pending_ = true;
        }
        ring->cv_.notify_all();
    }

    void thread_loop() {
        owner_.bind_current_thread();
        // Attached for the thread's lifetime, so the per-frame notify_render_update() is
        // a cheap GetEnv instead of an attach/detach pair.
        JavaVM *vm = owner_.jvm();
        JNIEnv *thread_env = nullptr;
        const bool attached = attach_current_thread(vm, &thread_env);

        const bool started = on_thread_start();
        {
            std::lock_guard<std::mutex> guard(mutex_);
            thread_started_ = true;
            thread_start_ok_ = started;
        }
        cv_.notify_all();
        if (started) run_loop();
        if (attached) vm->DetachCurrentThread();
    }

    void run_loop() {
        std::unique_lock<std::mutex> lock(mutex_);
        while (!quit_) {
            // A reconfig that still waits for the previous ring's ack is not work: counting
            // it would spin here with mutex_ held, and the ack itself needs mutex_.
            cv_.wait(lock, [this] {
                return quit_ || render_pending_ || (config_pending_ && !has_retired_buffers_) ||
                    retire_ack_pending_ || frame_request_pending_ || has_requests_locked();
            });
            if (quit_) break;

            if (retire_ack_pending_) {
                retire_ack_pending_ = false;
                if (has_retired_buffers_) {
                    destroy_ring(retired_buffers_);
                    has_retired_buffers_ = false;
                }
            }

            // A reconfig retires the current ring; never stack a second retirement on
            // top of an unacked one (the consumer may still be sampling it): postpone
            // until the ack arrives.
            bool configured = false;
            if (config_pending_ && !has_retired_buffers_) {
                config_pending_ = false;
                configured = apply_config_locked();
            }

            if (frame_request_pending_) {
                frame_request_pending_ = false;
                const int width = frame_request_width_, height = frame_request_height_;
                const uint64_t serial = frame_request_serial_;
                lock.unlock();
                std::vector<uint32_t> pixels;
                const bool ok = render_context_ && render_frame_pixels_on_render_thread(width, height, pixels);
                lock.lock();
                // A caller that gave up waiting has moved on, possibly to a newer request.
                if (frame_request_serial_ == serial) {
                    frame_request_pixels_ = std::move(pixels);
                    frame_request_ok_ = ok;
                    frame_request_finished_ = true;
                    cv_.notify_all();
                }
                continue;
            }

            if (serve_requests_locked(lock)) continue;

            const bool want_render = render_pending_;
            render_pending_ = false;

            if (!buffers_allocated_) {
                // With vo=libmpv, playback stalls unless someone consumes video frames.
                // While no surface is configured (headless probing, surface not composed
                // yet), discard them so the playback clock keeps advancing.
                if (want_render) {
                    lock.unlock();
                    drain_one_frame();
                    lock.lock();
                }
                continue;
            }

            bool has_new_frame = false;
            if (want_render && render_context_) {
                has_new_frame =
                    (mpv_render_context_update(render_context_) & MPV_RENDER_UPDATE_FRAME) != 0;
            }
            // After a reconfig, redraw the current frame into the new ring even if mpv
            // has nothing new (e.g. resizing while paused).
            if (!has_new_frame && !configured) continue;

            const int next = (latest_index_ + 1) % kBufferCount;
            const Buffer target = buffers_[next];
            lock.unlock();
            bool rendered = render_into(target);
            if (rendered && cpu_readback_) rendered = read_back(target);
            lock.lock();
            if (rendered) {
                if (cpu_readback_) publish_readback_locked();
                latest_index_ = next;
                ++frame_serial_;
                publish_state_locked();
                lock.unlock();
                // Only after the frame is complete and published, so a consumer waking
                // on this never samples a stale buffer.
                owner_.notify_render_update();
                lock.lock();
            }
        }
        // Rings are released on this thread: their GPU objects may belong to a context
        // that is only current here.
        release_rings_locked();
        lock.unlock();
        on_thread_exit();
    }

    bool apply_config_locked() {
        const int width = pending_width_, height = pending_height_;
        const int64_t device = pending_device_;
        const bool cpu_readback = pending_cpu_readback_;

        if (width <= 0 || height <= 0) {
            // Deactivate. The consumer drops all texture references before requesting
            // this, so both generations can be freed immediately.
            release_rings_locked();
            release_device_locked();
            device_prepared_ = false;
            buffer_device_ = 0;
            return false;
        }
        if (buffers_allocated_ && width == buffer_width_ && height == buffer_height_ &&
            device == buffer_device_ && cpu_readback == cpu_readback_) {
            return false;
        }

        if (buffers_allocated_ && cpu_readback_) {
            // A readback consumer never references ring textures (and never acks): free
            // the old ring now; it keeps drawing its last CPU copy meanwhile.
            destroy_ring(buffers_);
        } else if (buffers_allocated_) {
            for (int i = 0; i < kBufferCount; ++i) {
                retired_buffers_[i] = buffers_[i];
                buffers_[i] = Buffer{};
            }
            has_retired_buffers_ = true;
        }
        buffers_allocated_ = false;
        release_readback_locked();

        const bool device_changed = !device_prepared_ || device != buffer_device_;
        bool ok = prepare_device_locked(device, device_changed);
        device_prepared_ = ok;
        for (int i = 0; i < kBufferCount && ok; ++i) {
            ok = allocate_buffer(buffers_[i], width, height);
        }
        if (ok && cpu_readback) ok = setup_readback_locked();
        cpu_readback_ = ok && cpu_readback;
        if (!ok) {
            LOG(&owner_, LOG_LEVEL_ERROR, "%s: buffer ring allocation failed (%dx%d)", name_, width, height);
            destroy_ring(buffers_);
            release_readback_locked();
            latest_index_ = -1;
            buffer_width_ = buffer_height_ = 0;
            buffer_device_ = 0;
            ++buffer_generation_;
            publish_state_locked();
            return false;
        }

        buffers_allocated_ = true;
        buffer_width_ = width;
        buffer_height_ = height;
        buffer_device_ = device;
        latest_index_ = -1;
        ++buffer_generation_;
        publish_state_locked();
        LOG(&owner_, LOG_LEVEL_INFO, "%s: buffer ring allocated %dx%d gen=%u readback=%d",
            name_, width, height, buffer_generation_, cpu_readback_ ? 1 : 0);
        return true;
    }

    void release_rings_locked() {
        if (has_retired_buffers_) {
            destroy_ring(retired_buffers_);
            has_retired_buffers_ = false;
        }
        if (buffers_allocated_) {
            destroy_ring(buffers_);
            buffers_allocated_ = false;
        }
        release_readback_locked();
        cpu_readback_ = false;
        latest_index_ = -1;
        buffer_width_ = buffer_height_ = 0;
        ++buffer_generation_;
        publish_state_locked();
    }

    void destroy_ring(Buffer *ring) {
        for (int i = 0; i < kBufferCount; ++i) {
            destroy_buffer(ring[i]);
            ring[i] = Buffer{};
        }
    }

    void drain_one_frame() {
        if (!render_context_) return;
        mpv_render_context_update(render_context_);
        int skip = 1;
        mpv_render_param params[] = {
            {MPV_RENDER_PARAM_SKIP_RENDERING, &skip},
            {MPV_RENDER_PARAM_INVALID, nullptr},
        };
        mpv_render_context_render(render_context_, params);
    }

    void publish_state_locked() {
        const uint64_t index_bits = latest_index_ < 0 ? 0xFull : static_cast<uint64_t>(latest_index_);
        frame_state_.store(
            (static_cast<uint64_t>(buffer_generation_ & 0xFFFFu) << 48) |
            (index_bits << 44) |
            (static_cast<uint64_t>(buffer_width_ & 0x3FFF) << 30) |
            (static_cast<uint64_t>(buffer_height_ & 0x3FFF) << 16) |
            (frame_serial_ & 0xFFFFu),
            std::memory_order_release);
    }

    std::thread thread_;
    bool thread_started_ = false, thread_start_ok_ = false;
    bool render_pending_ = false;
    bool retire_ack_pending_ = false;
    bool config_pending_ = false;
    // One-off frame request (render_frame_pixels): posted by any thread, served here.
    bool frame_request_pending_ = false;
    bool frame_request_finished_ = false;
    bool frame_request_ok_ = false;
    int frame_request_width_ = 0, frame_request_height_ = 0;
    uint64_t frame_request_serial_ = 0;
    std::vector<uint32_t> frame_request_pixels_;
    int pending_width_ = 0, pending_height_ = 0;
    int64_t pending_device_ = 0;
    bool pending_cpu_readback_ = false;
    int64_t buffer_device_ = 0;
    bool device_prepared_ = false;
    uint32_t buffer_generation_ = 0;
    uint64_t frame_serial_ = 0;
    std::atomic<uint64_t> frame_state_{kNoFrameState};
};

} // namespace mediampv

#endif // MEDIAMPV_DESKTOP

#endif // MEDIAMP_SURFACE_RING_H
