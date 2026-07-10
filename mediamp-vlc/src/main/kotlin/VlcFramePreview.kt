/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.vlc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.features.FramePreview
import org.openani.mediamp.features.PreviewFrame
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.vlc.internal.io.SeekableInputCallbackMedia
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import kotlin.concurrent.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * [FramePreview] implementation backed by a second, headless VLC media player instance.
 *
 * The preview player renders into a small callback video surface (VLC scales the frame for us via
 * the buffer format), plays muted until the first frame arrives, then stays paused; each request
 * is a seek-while-paused, which makes VLC decode and display the frame at the target position.
 */
@OptIn(ExperimentalMediampApi::class)
internal class VlcFramePreview(
    /** The media currently playing in the main player, or `null` if none. */
    private val currentMediaData: () -> MediaData?,
) : FramePreview {
    private val mutex = Mutex()
    private var session: PreviewSession? = null
    private var factory: MediaPlayerFactory? = null
    private var closed = false

    override suspend fun getPreviewFrame(positionMillis: Long, maxWidth: Int, maxHeight: Int): PreviewFrame? {
        if (maxWidth <= 0 || maxHeight <= 0) return null
        val data = currentMediaData() ?: return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (closed) return@withLock null
                val session = obtainSessionLocked(data, maxWidth, maxHeight) ?: return@withLock null
                try {
                    session.grabFrame(positionMillis)
                } catch (e: CancellationException) {
                    // The caller moved on to a newer position (collectLatest). The session is
                    // still healthy — do not tear down the decoder on every scrub movement.
                    throw e
                } catch (e: Exception) {
                    // A broken session (e.g. VLC error state) should not be reused.
                    discardSessionLocked()
                    null
                }
            }
        }
    }

    /** Closes the preview session if the main player's media changed or was stopped. */
    suspend fun onMediaDataChanged(data: MediaData?) {
        mutex.withLock {
            if (session != null && session?.mediaData !== data) {
                discardSessionLocked()
            }
        }
    }

    suspend fun closeSuspending() {
        mutex.withLock {
            if (closed) return
            closed = true
            discardSessionLocked()
            factory?.release()
            factory = null
        }
    }

    private suspend fun obtainSessionLocked(data: MediaData, maxWidth: Int, maxHeight: Int): PreviewSession? {
        session?.let { existing ->
            if (existing.mediaData === data) return existing
            discardSessionLocked()
        }
        val factory = this.factory ?: VlcMediampPlayer.createPlayerLock.withLock {
            MediaPlayerFactory("--intf=dummy", "--quiet")
        }.also { this.factory = it }
        return try {
            // NonCancellable: session creation is expensive shared state; a cancelled first
            // request must not abort it half-way (the next request reuses the session).
            withContext(NonCancellable) {
                PreviewSession.create(factory, data, maxWidth, maxHeight)
            }.also { session = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun discardSessionLocked() {
        session?.close()
        session = null
    }

    private class PreviewSession private constructor(
        val mediaData: MediaData,
        private val player: EmbeddedMediaPlayer,
        private val surface: FrameGrabSurface,
        private val input: SeekableInput?,
    ) : AutoCloseable {
        private var started = false

        /** Last time (millis) reported by a libvlc timeChanged event. */
        private val lastTimeEvent = MutableStateFlow(-1L)

        init {
            player.events().addMediaPlayerEventListener(
                object : uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter() {
                    override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                        lastTimeEvent.value = newTime
                    }
                },
            )
        }

        suspend fun grabFrame(positionMillis: Long): PreviewFrame? {
            // NonCancellable: the initial muted play-until-first-frame is shared progress that
            // must survive the cancellation of the request that happened to trigger it.
            if (!withContext(NonCancellable) { ensureStarted() }) return null

            val length = player.status().length()
            val target = if (length > 0) positionMillis.coerceIn(0, max(0, length - 500)) else max(0, positionMillis)

            // A paused seek makes VLC only re-display the stale pre-seek picture; it does not
            // decode the target. The reliable recipe (same as vlcj's thumbnailer example):
            // briefly unpause, seek, wait for a timeChanged event near the target (= demuxer
            // and decoder actually landed there), take the next displayed frame, pause again.
            lastTimeEvent.value = -1L // ignore events from before this seek
            player.submit {
                player.controls().setTime(target)
                player.controls().setPause(false)
            }
            try {
                val seekSettled = withTimeoutOrNull(5_000) {
                    lastTimeEvent.first { it >= 0 && kotlin.math.abs(it - target) < 2_000 }
                    true
                } ?: false
                if (!seekSettled) return null

                // The first frame right after the seek can still be the flushed re-display of the
                // old picture; frames arriving after the confirmed position are freshly decoded.
                val frameBefore = surface.frameCounter.value
                if (!surface.awaitFrameAfter(frameBefore, timeoutMillis = 1_000)) return null
            } finally {
                player.submit { player.controls().setPause(true) }
            }
            return surface.copyLatestFrame(target)
        }

        /** Starts muted playback once, waits for the first frame, then pauses. */
        private suspend fun ensureStarted(): Boolean {
            if (started) return true
            player.submit { player.controls().start() }
            val gotFirstFrame = surface.awaitFrameAfter(0, timeoutMillis = 10_000)
            if (!gotFirstFrame) return false
            player.submit { player.controls().setPause(true) }
            started = true
            return true
        }

        override fun close() {
            try {
                player.release()
            } catch (_: Exception) {
            } finally {
                try {
                    input?.close()
                } catch (_: Exception) {
                }
            }
        }

        companion object {
            /** Media options keeping the preview player lightweight. */
            private val PREVIEW_OPTIONS = listOf("no-audio", "no-spu")

            suspend fun create(
                factory: MediaPlayerFactory,
                data: MediaData,
                maxWidth: Int,
                maxHeight: Int,
            ): PreviewSession {
                // Open the (possibly slow) input before touching VLC, so a failure here leaks nothing.
                val input: SeekableInput? = when (data) {
                    is SeekableInputMediaData -> data.createInput()
                    is UriMediaData -> null
                }

                val player = try {
                    VlcMediampPlayer.createPlayerLock.withLock {
                        factory.mediaPlayers().newEmbeddedMediaPlayer()
                    }
                } catch (e: Throwable) {
                    input?.close()
                    throw e
                }
                val surface = FrameGrabSurface(maxWidth, maxHeight)
                player.videoSurface().set(surface)
                surface.attach(player)

                try {
                    val prepared = when (data) {
                        is UriMediaData -> {
                            val lowerHeaders = data.headers.mapKeys { it.key.lowercase() }
                            val options = buildList {
                                addAll(PREVIEW_OPTIONS)
                                add("http-user-agent=${lowerHeaders["user-agent"] ?: "Mozilla/5.0"}")
                                lowerHeaders["referer"]?.let { add("http-referrer=$it") }
                                addAll(data.options)
                            }
                            player.media().prepare(data.uri, *options.toTypedArray())
                        }

                        is SeekableInputMediaData -> {
                            val media = SeekableInputCallbackMedia(input!!) {}
                            player.media().prepare(media, *(PREVIEW_OPTIONS + data.options).toTypedArray())
                        }
                    }
                    check(prepared) { "VLC prepare() failed for preview media" }
                } catch (e: Throwable) {
                    player.release()
                    input?.close()
                    throw e
                }
                return PreviewSession(data, player, surface, input)
            }
        }
    }

    /**
     * A headless callback video surface that keeps only the most recent frame.
     *
     * The buffer format requests a downscaled picture from VLC, so decoding cost stays low and
     * no scaling is needed on our side.
     */
    private class FrameGrabSurface(
        private val maxWidth: Int,
        private val maxHeight: Int,
    ) : VideoSurface(VideoSurfaceAdapters.getVideoSurfaceAdapter()) {
        /** Incremented on every displayed frame. Never reset. */
        val frameCounter = MutableStateFlow(0L)

        private val lock = Any()
        private var frameWidth = 0
        private var frameHeight = 0
        private var frameBytes: ByteArray? = null

        private val bufferFormatCallback = object : BufferFormatCallback {
            private var width = 0
            private var height = 0

            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                val scale = min(
                    maxWidth.toFloat() / sourceWidth,
                    maxHeight.toFloat() / sourceHeight,
                ).coerceAtMost(1f)
                width = max(2, (sourceWidth * scale).roundToInt())
                height = max(2, (sourceHeight * scale).roundToInt())
                return RV32BufferFormat(width, height)
            }

            override fun allocatedBuffers(buffers: Array<ByteBuffer>) {
                synchronized(lock) {
                    frameWidth = width
                    frameHeight = height
                    frameBytes = ByteArray(buffers[0].remaining())
                }
            }
        }

        private val renderCallback = object : RenderCallback {
            override fun display(
                mediaPlayer: MediaPlayer,
                nativeBuffers: Array<ByteBuffer>,
                bufferFormat: BufferFormat,
            ) {
                synchronized(lock) {
                    val bytes = frameBytes ?: return
                    val buffer = nativeBuffers[0]
                    buffer.rewind()
                    if (buffer.remaining() < bytes.size) return
                    buffer.get(bytes, 0, bytes.size)
                }
                frameCounter.value += 1
            }
        }

        private val callbackSurface = object : CallbackVideoSurface(
            bufferFormatCallback,
            renderCallback,
            true,
            VideoSurfaceAdapters.getVideoSurfaceAdapter(),
        ) {}

        override fun attach(mediaPlayer: MediaPlayer) {
            callbackSurface.attach(mediaPlayer)
        }

        suspend fun awaitFrameAfter(count: Long, timeoutMillis: Long): Boolean {
            return withTimeoutOrNull(timeoutMillis) {
                frameCounter.first { it > count }
                true
            } ?: false
        }

        /** Converts the latest BGRA frame to a [PreviewFrame], or `null` if none arrived yet. */
        fun copyLatestFrame(positionMillis: Long): PreviewFrame? {
            synchronized(lock) {
                val bytes = frameBytes ?: return null
                val width = frameWidth
                val height = frameHeight
                if (width <= 0 || height <= 0 || frameCounter.value == 0L) return null
                val pixels = IntArray(width * height)
                // RV32 is BGRA byte order.
                var i = 0
                for (p in pixels.indices) {
                    val b = bytes[i].toInt() and 0xFF
                    val g = bytes[i + 1].toInt() and 0xFF
                    val r = bytes[i + 2].toInt() and 0xFF
                    val a = bytes[i + 3].toInt() and 0xFF
                    pixels[p] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    i += 4
                }
                return PreviewFrame(positionMillis, width, height, pixels)
            }
        }
    }
}
