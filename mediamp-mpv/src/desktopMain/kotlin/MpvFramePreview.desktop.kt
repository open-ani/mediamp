/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.features.FramePreview
import org.openani.mediamp.features.PreviewFrame
import org.openani.mediamp.mpv.internal.MpvPreviewDecoder
import org.openani.mediamp.mpv.internal.MpvSurfaceRingBackend
import org.openani.mediamp.mpv.internal.currentSurfaceRingBackend
import org.openani.mediamp.source.MediaData
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal actual fun createMpvFramePreview(
    player: JvmMpvMediampPlayer,
    context: Any,
    parentCoroutineContext: CoroutineContext,
): FramePreview? {
    // Without a surface-ring backend (Linux, TODO) frames cannot be read back, so the
    // feature is absent rather than present-but-always-null.
    val ringBackend = currentSurfaceRingBackend() ?: return null
    return MpvFramePreview(player, context, ringBackend, parentCoroutineContext)
}

/**
 * [FramePreview] implementation backed by a second, minimal mpv instance
 * ([MpvPreviewDecoder] — no event listeners, no player features, tiny demuxer cache).
 *
 * The decoder loads the same media (a second input for stream_cb media), stays paused,
 * and renders into a small native surface ring sized per request; each request is a
 * keyframe seek followed by a direct pixel readback.
 */
@OptIn(ExperimentalMediampApi::class)
internal class MpvFramePreview(
    private val mainPlayer: JvmMpvMediampPlayer,
    private val context: Any,
    private val ringBackend: MpvSurfaceRingBackend,
    parentCoroutineContext: CoroutineContext,
) : FramePreview, AutoCloseable {
    private val scope = CoroutineScope(
        parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job.Key]),
    )
    private val mutex = Mutex()
    private var session: PreviewSession? = null
    private var closed = false

    init {
        scope.launch {
            // Tear down the preview decoder when the main player's media changes or stops,
            // so it never outlives the media data it reads from.
            mainPlayer.mediaData.collect { data ->
                mutex.withLock {
                    if (session != null && session?.mediaData !== data) {
                        discardSessionLocked()
                    }
                }
            }
        }
    }

    override suspend fun getPreviewFrame(positionMillis: Long, maxWidth: Int, maxHeight: Int): PreviewFrame? {
        if (maxWidth <= 0 || maxHeight <= 0) return null
        val data = mainPlayer.currentMediaDataOrNull() ?: return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (closed) return@withLock null
                val session = obtainSessionLocked(data) ?: return@withLock null
                try {
                    session.grabFrame(positionMillis, maxWidth, maxHeight)
                } catch (e: CancellationException) {
                    // The caller moved on to a newer position (collectLatest). The session is
                    // still healthy — destroying it here would tear down and rebuild the whole
                    // decoder instance on every scrub movement.
                    throw e
                } catch (e: Exception) {
                    // A broken session (e.g. dead mpv instance) should not be reused.
                    discardSessionLocked()
                    null
                }
            }
        }
    }

    private suspend fun obtainSessionLocked(data: MediaData): PreviewSession? {
        session?.let { existing ->
            if (existing.mediaData === data) return existing
            discardSessionLocked()
        }
        return try {
            // NonCancellable: decoder creation is expensive shared state; a cancelled first
            // request must not abort it half-way (the next request reuses the session).
            withContext(NonCancellable) {
                val decoder = MpvPreviewDecoder(context, ringBackend)
                try {
                    PreviewSession(data, decoder, scope)
                } catch (e: Throwable) {
                    decoder.close()
                    throw e
                }
            }.also { session = it }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun discardSessionLocked() {
        session?.closeSuspending()
        session = null
    }

    override fun close() {
        // Called from the main player's closeImpl (main thread): do the actual native teardown
        // off-thread, serialized behind the mutex against any in-flight grab.
        scope.launch(NonCancellable) {
            mutex.withLock {
                if (closed) return@withLock
                closed = true
                discardSessionLocked()
            }
            scope.cancel()
        }
    }

    private class PreviewSession(
        /** The main player's media data; identity-compared to detect media changes. */
        val mediaData: MediaData,
        private val decoder: MpvPreviewDecoder,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        private val renderCounter = MutableStateFlow(0L)

        private enum class StartOutcome {
            READY,

            /**
             * The media has no video track, or the load terminated without producing one
             * (e.g. a corrupt file). Permanent for this session.
             */
            UNPLAYABLE,

            /** Transient (e.g. the data is not downloaded yet); retried by the next request. */
            FAILED,
        }

        /**
         * The shared load-and-probe attempt. Runs on the session [scope] so a caller
         * cancelled mid-wait (scrubbing) does not abort it; the await in [ensureStarted]
         * stays cancellable, unlike the previous NonCancellable block which pinned a
         * cancelled caller (and the mutex) for the full 10s wait on unavailable data.
         *
         * All fields are confined to the preview mutex except [cachedOutcome], which the
         * attempt writes from its own coroutine.
         */
        private var startAttempt: Deferred<StartOutcome>? = null

        @Volatile
        private var cachedOutcome: StartOutcome? = null

        /** Display dimensions of the video (rotation/anamorphic-corrected), set by a READY attempt. */
        private var videoWidth = 0
        private var videoHeight = 0

        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var surfaceHasFrame = false

        init {
            decoder.setRenderUpdateListener { renderCounter.update { it + 1 } }
        }

        suspend fun grabFrame(positionMillis: Long, maxWidth: Int, maxHeight: Int): PreviewFrame? {
            if (!ensureStarted()) return null
            if (!ensureSurface(maxWidth, maxHeight)) return null
            val handle = decoder.handle

            val durationMillis = (handle.getPropertyDouble("duration") * 1000).toLong()
            val target = if (durationMillis > 0) {
                positionMillis.coerceIn(0, max(0, durationMillis - 500))
            } else {
                max(0, positionMillis)
            }

            val counterBefore = renderCounter.value
            val timePosBeforeMillis = (handle.getPropertyDouble("time-pos") * 1000).toLong()
            if (!handle.command("seek", formatSecondsForMpv(target / 1000.0), "absolute+keyframes")) {
                return null
            }

            // Wait for the seek to finish. time-pos is deliberately NOT required to land near
            // the target: `keyframes` snaps to the previous keyframe, which on long-GOP content
            // (keyint of 5-10s is common) can be arbitrarily far from the target — a distance
            // check would permanently blank previews for such positions. Completion is instead
            // a full seeking true->false cycle, an observed position change, or a fresh render.
            // A no-op seek (the target maps to the already-displayed keyframe) may show none of
            // these, so a short quiet window also counts as done.
            var moved = false
            val landed = withTimeoutOrNull(5_000) {
                var sawSeeking = false
                var quietPolls = 0
                while (true) {
                    if (renderCounter.value > counterBefore) break
                    val seeking = handle.getPropertyBoolean("seeking")
                    val timePosMillis = (handle.getPropertyDouble("time-pos") * 1000).toLong()
                    if (timePosMillis != timePosBeforeMillis) moved = true
                    if (seeking) sawSeeking = true
                    if (!seeking && (sawSeeking || moved)) break
                    if (!seeking && ++quietPolls >= 15) break
                    delay(20)
                }
                true
            } ?: false
            if (!landed) return null

            if (renderCounter.value == counterBefore && moved) {
                // The decoder landed on a different frame, so a fresh render is coming; without
                // it the surface still holds the pre-seek picture. If it does not arrive in
                // time we read anyway — a slightly stale thumbnail beats none. When !moved the
                // displayed keyframe already is the target and no render will come at all;
                // waiting here used to cost every same-keyframe request a flat 500ms.
                withTimeoutOrNull(1_000) { renderCounter.first { it > counterBefore } }
            }

            val dims = IntArray(2)
            val pixels = decoder.readSurfacePixels(dims) ?: return null
            return PreviewFrame(target, dims[0], dims[1], pixels)
        }

        private suspend fun ensureStarted(): Boolean {
            // Fast path: a completed attempt already decided this session's fate.
            when (cachedOutcome) {
                StartOutcome.READY -> return true
                StartOutcome.UNPLAYABLE -> return false
                StartOutcome.FAILED, null -> {}
            }
            val attempt = startAttempt?.takeIf { it.isActive }
                ?: scope.async(Dispatchers.IO) { doStart().also { cachedOutcome = it } }
                    .also { startAttempt = it }
            return attempt.await() == StartOutcome.READY
        }

        /** Loads the media paused and waits for the video dimensions of the first frame. */
        private suspend fun doStart(): StartOutcome {
            if (!decoder.loadPaused(mediaData)) return StartOutcome.FAILED

            var unplayable = false
            var codedFallbackPolls = 0
            var sawLoading = false
            var idlePolls = 0
            val dims = withTimeoutOrNull(10_000) {
                while (true) {
                    // Display dimensions include rotation and anamorphic aspect; the coded
                    // width/height would distort such content. They normally appear together,
                    // but fall back to the coded size if only that becomes available.
                    val dw = decoder.handle.getPropertyInt("dwidth")
                    val dh = decoder.handle.getPropertyInt("dheight")
                    if (dw > 0 && dh > 0) return@withTimeoutOrNull dw to dh
                    val w = decoder.handle.getPropertyInt("width")
                    val h = decoder.handle.getPropertyInt("height")
                    if (w > 0 && h > 0 && ++codedFallbackPolls >= 20) return@withTimeoutOrNull w to h

                    // A load that TERMINATES without video dimensions (audio-only file with
                    // aid=no selects no track at all; corrupt file) drops mpv back to idle.
                    // Waiting out the full timeout — and re-waiting on every request — would
                    // burn 10s per hover, so detect the idle transition. This cannot misfire
                    // for merely-slow media: a demuxer blocked on unavailable data (torrent
                    // inputs block rather than error) keeps idle-active false the whole time.
                    val idleActive = decoder.handle.getPropertyBoolean("idle-active")
                    if (!idleActive) {
                        sawLoading = true
                        idlePolls = 0
                    } else if (sawLoading || ++idlePolls >= 40) {
                        // Load ended (or, after 2s of continuous idle, never started).
                        unplayable = true
                        return@withTimeoutOrNull null
                    }
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
            if (unplayable) return StartOutcome.UNPLAYABLE
            if (dims == null) return StartOutcome.FAILED
            videoWidth = dims.first
            videoHeight = dims.second
            return StartOutcome.READY
        }

        /**
         * Sizes the surface ring to fit the video within [maxWidth] x [maxHeight] and makes
         * sure a frame has landed in it. Re-checked on every request: the max size may
         * legitimately change between requests, and returning frames larger than the
         * requested bounds would break the [FramePreview.getPreviewFrame] contract.
         */
        private suspend fun ensureSurface(maxWidth: Int, maxHeight: Int): Boolean {
            val scale = min(
                maxWidth.toFloat() / videoWidth,
                maxHeight.toFloat() / videoHeight,
            ).coerceAtMost(1f)
            val desiredWidth = max(2, (videoWidth * scale).roundToInt())
            val desiredHeight = max(2, (videoHeight * scale).roundToInt())
            if (desiredWidth != surfaceWidth || desiredHeight != surfaceHeight) {
                if (!decoder.requestSurface(desiredWidth, desiredHeight)) return false
                surfaceWidth = desiredWidth
                surfaceHeight = desiredHeight
                surfaceHasFrame = false
            }
            if (!surfaceHasFrame) {
                // After a reconfig the new ring is empty until the render thread redraws the
                // current frame into it (this happens even while paused). The probe read
                // covers a frame that landed while no request was waiting (e.g. the request
                // that reconfigured was cancelled mid-wait).
                val counterNow = renderCounter.value
                surfaceHasFrame = decoder.readSurfacePixels(IntArray(2)) != null ||
                    withTimeoutOrNull(5_000) { renderCounter.first { it > counterNow } } != null
            }
            return surfaceHasFrame
        }

        /**
         * Cancels the in-flight start attempt (waiting for it to actually finish, so no
         * coroutine still touches the handle) and then tears down the native decoder.
         */
        suspend fun closeSuspending() {
            startAttempt?.cancelAndJoin()
            close()
        }

        override fun close() {
            decoder.close()
        }
    }
}

private fun formatSecondsForMpv(seconds: Double): String {
    // mpv parses decimal seconds; String.format would be locale-sensitive.
    return ((seconds * 1000).toLong() / 1000.0).toBigDecimal().toPlainString()
}
