/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.features.FramePreview
import org.openani.mediamp.features.PreviewFrame
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.mpv.utils.OpenGLRenderEnvironment
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal actual fun createMpvFramePreview(
    player: JvmMpvMediampPlayer,
    context: Any,
    parentCoroutineContext: CoroutineContext,
): FramePreview? {
    // The preview decoder is itself an MpvMediampPlayer, which would recursively get its own
    // (never-used) FramePreview. Cut the recursion at depth 1.
    if (context is MpvFramePreviewContextMarker) return null
    return MpvFramePreview(player, context, parentCoroutineContext)
}

/** Marks the `context` of a preview decoder instance so it does not create nested previews. */
internal class MpvFramePreviewContextMarker(val delegate: Any)

/**
 * [FramePreview] implementation backed by a second, headless mpv instance.
 *
 * The preview instance loads the same media (a second [SeekableInput] for stream_cb media),
 * stays paused, and renders into a small native surface ring sized to fit the requested
 * dimensions; each request is a keyframe seek followed by a surface readback.
 */
@OptIn(ExperimentalMediampApi::class)
internal class MpvFramePreview(
    private val mainPlayer: JvmMpvMediampPlayer,
    private val context: Any,
    private val parentCoroutineContext: CoroutineContext,
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
                    if (session != null && session?.originalData !== data) {
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
                val session = obtainSessionLocked(data, maxWidth, maxHeight) ?: return@withLock null
                try {
                    session.grabFrame(positionMillis)
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

    private suspend fun obtainSessionLocked(data: MediaData, maxWidth: Int, maxHeight: Int): PreviewSession? {
        session?.let { existing ->
            if (existing.originalData === data) return existing
            discardSessionLocked()
        }
        // A new Linux preview player needs a GLX environment before vo=libmpv can load.
        val renderEnvironment = (mainPlayer as? MpvMediampPlayer)?.currentOpenGLRenderEnvironment()
        if (hostOs == OS.Linux && renderEnvironment == null) return null
        return try {
            // NonCancellable: session creation is expensive shared state; a cancelled first
            // request must not abort it half-way (the next request reuses the session).
            withContext(NonCancellable) {
                PreviewSession.create(context, parentCoroutineContext, data, maxWidth, maxHeight, renderEnvironment)
            }.also { session = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun discardSessionLocked() {
        session?.close()
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

    private class PreviewSession private constructor(
        /** The main player's media data; identity-compared to detect media changes. */
        val originalData: MediaData,
        private val previewData: MediaData,
        private val player: MpvMediampPlayer,
        private val renderCounter: MutableStateFlow<Long>,
        private val maxWidth: Int,
        private val maxHeight: Int,
    ) : AutoCloseable {
        private var started = false

        suspend fun grabFrame(positionMillis: Long): PreviewFrame? {
            // NonCancellable: starting (paused load + surface setup) is shared progress that
            // must survive the cancellation of the request that happened to trigger it.
            if (!withContext(NonCancellable) { ensureStarted() }) return null
            val handle = player.handle

            val durationMillis = (handle.getPropertyDouble("duration") * 1000).toLong()
            val target = if (durationMillis > 0) {
                positionMillis.coerceIn(0, max(0, durationMillis - 500))
            } else {
                max(0, positionMillis)
            }

            val counterBefore = renderCounter.value
            if (!handle.command("seek", formatSecondsForMpv(target / 1000.0), "absolute+keyframes")) {
                return null
            }

            // Wait until the seek lands: while paused, mpv decodes the target frame, updates
            // time-pos and pushes a render update.
            val landed = withTimeoutOrNull(5_000) {
                while (true) {
                    val seeking = handle.getPropertyBoolean("seeking")
                    val timePosMillis = (handle.getPropertyDouble("time-pos") * 1000).toLong()
                    if (!seeking && abs(timePosMillis - target) < 3_000) break
                    delay(20)
                }
                true
            } ?: false
            if (!landed) return null

            // Prefer a frame rendered after the seek. The surface ring only publishes complete
            // frames, so the first post-seek render is safe to read immediately. When the seek
            // lands on the keyframe that is already displayed, mpv may not push a new render —
            // the surface content is still correct, so a timeout here is not an error.
            withTimeoutOrNull(500) { renderCounter.first { it > counterBefore } }

            val tmp = File.createTempFile("mediamp-mpv-preview", ".png")
            try {
                if (!player.dumpSurfaceForDebug(tmp.absolutePath)) return null
                val image = ImageIO.read(tmp) ?: return null
                val pixels = IntArray(image.width * image.height)
                image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                return PreviewFrame(target, image.width, image.height, pixels)
            } finally {
                tmp.delete()
            }
        }

        /** Loads the media paused, sizes the surface ring to the video, waits for the first frame. */
        private suspend fun ensureStarted(): Boolean {
            if (started) return true
            val handle = player.handle
            if (!player.commandLoadFilePaused()) return false

            // Video dimensions become available once the first frame is decoded.
            val dims = withTimeoutOrNull(10_000) {
                while (true) {
                    val w = handle.getPropertyInt("width")
                    val h = handle.getPropertyInt("height")
                    if (w > 0 && h > 0) return@withTimeoutOrNull w to h
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            } ?: return false

            val (videoWidth, videoHeight) = dims
            val scale = min(
                maxWidth.toFloat() / videoWidth,
                maxHeight.toFloat() / videoHeight,
            ).coerceAtMost(1f)
            val surfaceWidth = max(2, (videoWidth * scale).roundToInt())
            val surfaceHeight = max(2, (videoHeight * scale).roundToInt())
            if (!player.requestSurface(surfaceWidth, surfaceHeight, 0L)) return false

            if (withTimeoutOrNull(5_000) { renderCounter.first { it > 0 } } == null) return false
            started = true
            return true
        }

        override fun close() {
            try {
                player.setRenderUpdateListener(null)
                player.releaseSurface()
                player.releaseRenderContext()
            } catch (_: Exception) {
            }
            try {
                player.close()
            } catch (_: Exception) {
            }
            (previewData as? NonClosingSeekableInputMediaData)?.closeOpenInputs()
        }

        companion object {
            suspend fun create(
                context: Any,
                parentCoroutineContext: CoroutineContext,
                data: MediaData,
                maxWidth: Int,
                maxHeight: Int,
                renderEnvironment: OpenGLRenderEnvironment?,
            ): PreviewSession {
                val previewData: MediaData = when (data) {
                    is SeekableInputMediaData -> NonClosingSeekableInputMediaData(data)
                    // UriMediaData is sealed; a fresh copy is safe because its close() is a no-op.
                    is UriMediaData -> UriMediaData(data.uri, data.headers, data.extraFiles, data.options)
                }
                val renderCounter = MutableStateFlow(0L)
                val player = MpvMediampPlayer(MpvFramePreviewContextMarker(context), parentCoroutineContext)
                try {
                    if (hostOs == OS.Linux) {
                        checkNotNull(renderEnvironment) { "Linux frame preview requires the main player's GLX environment" }
                        check(player.attachOpenGLRenderEnvironment(renderEnvironment)) {
                            "Could not attach the main player's GLX environment to the preview decoder"
                        }
                    }
                    val handle = player.handle
                    // Keep the preview decoder lightweight: no audio, no subtitles.
                    handle.setPropertyString("aid", "no")
                    handle.setPropertyString("sid", "no")
                    handle.setPropertyBoolean("pause", true)
                    player.setRenderUpdateListener { renderCounter.update { it + 1 } }
                    player.setMediaData(previewData)
                    return PreviewSession(data, previewData, player, renderCounter, maxWidth, maxHeight)
                } catch (e: Throwable) {
                    try {
                        player.close()
                    } catch (_: Exception) {
                    }
                    (previewData as? NonClosingSeekableInputMediaData)?.closeOpenInputs()
                    throw e
                }
            }
        }
    }

    /**
     * Delegates to the main player's media but never closes it (the main player owns it),
     * and tracks inputs opened for the preview decoder so the session can close them.
     */
    private class NonClosingSeekableInputMediaData(
        private val delegate: SeekableInputMediaData,
    ) : SeekableInputMediaData {
        private val openInputs = CopyOnWriteArrayList<SeekableInput>()

        override val uri: String get() = delegate.uri
        override val extraFiles: MediaExtraFiles get() = delegate.extraFiles
        override val options: List<String> get() = delegate.options
        override fun fileLength(): Long? = delegate.fileLength()

        override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput =
            delegate.createInput(coroutineContext).also { openInputs.add(it) }

        override fun close() {
            // no-op: the shared media data is owned by the main player.
        }

        fun closeOpenInputs() {
            openInputs.forEach {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }
            openInputs.clear()
        }
    }
}

private fun formatSecondsForMpv(seconds: Double): String {
    // mpv parses decimal seconds; String.format would be locale-sensitive.
    return ((seconds * 1000).toLong() / 1000.0).toBigDecimal().toPlainString()
}
