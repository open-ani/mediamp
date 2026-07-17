/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.openani.mediamp.AbstractMediampPlayer
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.mpv.internal.MpvPlaybackStateMachine
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.FramePreview
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.Screenshots
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.features.buildPlayerFeatures
import org.openani.mediamp.internal.Platform
import org.openani.mediamp.internal.currentPlatform
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import kotlin.coroutines.CoroutineContext

private const val SEEKABLE_INPUT_LOAD_TARGET_PREFIX = "mediamp://seekble_input_media/"

private fun buildSeekableInputLoadTarget(data: SeekableInputMediaData): String {
    return SEEKABLE_INPUT_LOAD_TARGET_PREFIX + data.uri
}

/**
 * How stale position reports are filtered during a seek: mpv keeps reporting the
 * pre-seek time until the demuxer/decoder lands on the target, which would make the
 * progress bar jump backwards. While "seeking" is true we keep [currentPositionMillis]
 * at the user's intent and drop mpv's reports.
 */
@kotlin.OptIn(InternalMediampApi::class)
abstract class JvmMpvMediampPlayer(
    context: Any,
    parentCoroutineContext: CoroutineContext,
) : AbstractMediampPlayer<JvmMpvMediampPlayer.MPVPlayerData>(parentCoroutineContext) {
    open class MPVPlayerData(
        mediaData: MediaData,
        val loadTarget: String,
    ) : Data(mediaData)

    private class SeekableInputPlayerData(
        mediaData: SeekableInputMediaData,
        loadTarget: String,
        private val handle: MPVHandle,
    ) : MPVPlayerData(mediaData, loadTarget) {
        override fun release() {
            handle.unregisterSeekableInput(loadTarget)
            super.release()
        }
    }

    internal val handle by lazy { MPVHandle(context) }
    private val stateMachine = MpvPlaybackStateMachine()

    // Linux may resume before Skiko exposes its GLX share context. Preserve that load
    // until surface attachment; eager macOS/Windows contexts never use this state.
    private val pendingLoadLock = Any()
    private var pendingPlaybackLoad: MPVPlayerData? = null

    override val impl: Any get() = handle

    private val _currentPositionMillis: MutableStateFlow<Long> = MutableStateFlow(0L)
    override val currentPositionMillis: StateFlow<Long> = _currentPositionMillis

    private val _mediaProperties: MutableStateFlow<MediaProperties?> = MutableStateFlow(null)
    override val mediaProperties: StateFlow<MediaProperties?> = _mediaProperties

    private val playbackSpeed = MpvPlaybackSpeed(handle)
    private val audioLevelController = MpvAudioLevelController(handle)
    private val buffering = MpvBuffering(playbackState)
    private val screenshots = MpvScreenshots { path -> takeScreenshotImpl(path) }
    private val videoAspectRatio = MpvVideoAspectRatio(handle)
    private val mediaMetadata = MpvMediaMetadata(handle)
    private val framePreview: FramePreview? = createMpvFramePreview(this, context, parentCoroutineContext)

    override val features: PlayerFeatures = buildPlayerFeatures {
        add(PlaybackSpeed.Key, playbackSpeed)
        add(AudioLevelController.Key, audioLevelController)
        add(Buffering.Key, buffering)
        add(Screenshots.Key, screenshots)
        add(VideoAspectRatio.Key, videoAspectRatio)
        add(MediaMetadata, mediaMetadata)
        framePreview?.let { add(FramePreview.Key, it) }
    }

    private val eventListener = object : EventListener {
        override fun onPropertyChange(name: String) {
            when (name) {
                "track-list" -> mediaMetadata.refreshTracks()
                "chapter-list" -> mediaMetadata.refreshChapters()
            }
        }

        override fun onPropertyChange(name: String, value: Boolean) {
            when (name) {
                "pause" -> {
                    stateMachine.onPauseProperty(value, playbackState.value)?.let { playbackState.value = it }
                }

                "paused-for-cache" -> {
                    stateMachine.onPausedForCacheProperty(value, playbackState.value)?.let { playbackState.value = it }
                }

                "seeking" -> stateMachine.onSeekingProperty(value)

                "mute" -> audioLevelController.onMuteChanged(value)

                "eof-reached" -> {
                    stateMachine.onEofReachedProperty(value, playbackState.value)?.let { playbackState.value = it }
                }

                "idle-active" -> {
                    stateMachine.onIdleActiveProperty(value, playbackState.value)?.let { playbackState.value = it }
                }
            }
        }

        override fun onPropertyChange(name: String, value: Long) {
            when (name) {
                "cache-buffering-state" -> buffering.bufferedPercentage.value = value.toInt().coerceIn(0, 100)
            }
        }

        override fun onPropertyChange(name: String, value: Double) {
            when (name) {
                "time-pos" -> {
                    if (!stateMachine.shouldIgnoreTimePos()) {
                        _currentPositionMillis.value = (value * 1000).toLong()
                    }
                }

                "duration" -> {
                    val durationMillis = (value * 1000).toLong()
                    _mediaProperties.value = _mediaProperties.value?.copy(durationMillis = durationMillis)
                        ?: MediaProperties(null, durationMillis)
                }

                "speed" -> playbackSpeed.onSpeedChanged(value)
                "volume" -> audioLevelController.onVolumeChanged(value)
            }
        }

        override fun onPropertyChange(name: String, value: String) {
            when (name) {
                "media-title" -> _mediaProperties.value = _mediaProperties.value?.copy(title = value)
                    ?: MediaProperties(value, -1)
            }
        }

        override fun onEvent(event: Int) {
        }

        override fun onEndFile(reason: Int, mpvError: Int) {
            stateMachine.onEndFile(reason, playbackState.value)?.let { playbackState.value = it }
        }
    }

    override fun getCurrentMediaProperties(): MediaProperties? = mediaProperties.value

    override fun getCurrentPlaybackState(): PlaybackState = playbackState.value

    override fun getCurrentPositionMillis(): Long = currentPositionMillis.value

    private fun clearPlaybackSession(resetPosition: Boolean) {
        stateMachine.reset()
        _mediaProperties.value = null
        mediaMetadata.clear()
        if (resetPosition) {
            _currentPositionMillis.value = 0L
        }
    }

    internal fun setRenderUpdateListener(listener: RenderUpdateListener?): Boolean {
        return handle.setRenderUpdateListener(listener)
    }

    /**
     * Writes the current video frame to [path] as an image. The default uses mpv's
     * screenshot command, which cannot convert hwdec frames on all builds; platform
     * subclasses may override with a native readback.
     */
    protected open suspend fun takeScreenshotImpl(path: String): Boolean {
        return handle.command("screenshot-to-file", path, "video")
    }

    init {
        // Resolve the native handle now: if its creation fails, nMake throws with nothing
        // to release. If any later configuration step fails (e.g. initialize() throwing),
        // close the handle so a failed construction never leaks the native mpv instance,
        // then rethrow for the caller to handle.
        val nativeHandle = handle
        try {
            configureNativeHandle(nativeHandle)
        } catch (e: Throwable) {
            nativeHandle.close()
            throw e
        }
    }

    private fun configureNativeHandle(handle: MPVHandle) {
        handle.setEventListener(eventListener)

        handle.option("config", "no")
        handle.option("profile", "fast")

        when (currentPlatform()) {
            is Platform.Android -> {
                handle.option("gpu-context", "android")
                handle.option("opengl-es", "yes")
                handle.option("ao", "audiotrack,opensles")
                handle.option("vo", "gpu-next")
            }

            is Platform.Windows -> {
                // The desktop render path drives the libmpv D3D11 render API on its own
                // ID3D11Device (render_d3d11.cpp); gpu-context is not used with vo=libmpv.
                handle.option("ao", "wasapi")
                handle.option("vo", "libmpv")
            }

            is Platform.MacOS -> {
                // The render API provides its own offscreen CGL context (render_macos.mm);
                // gpu-context is not used with vo=libmpv.
                handle.option("ao", "coreaudio")
                handle.option("vo", "libmpv")
            }

            is Platform.Linux -> {
                // The desktop GLX bridge creates libmpv's OpenGL render context only after
                // Skiko exposes its live share context; ao is picked at playback time.
                handle.option("ao", "pulse,alsa")
                handle.option("vo", "libmpv")
            }

            else -> {}
        }

        when (currentPlatform()) {
            is Platform.Windows, is Platform.MacOS, is Platform.Linux -> {
                // HDR -> SDR tone-mapping. The desktop render API (render_d3d11.cpp /
                // render_macos.mm) draws into an 8-bit SDR (sRGB) texture. By default
                // mpv's renderer enters "dumb mode" (a fast passthrough blit) and writes
                // HDR (PQ/bt2020) frames untonemapped — everything but the brightest
                // highlights is crushed to black (symptom: an HDR video plays with audio
                // but a near-black picture). check_dumb_mode() only inspects scaling /
                // debanding / shaders, never the target colorspace, so the target-*
                // options below cannot leave dumb mode on their own; gpu-dumb-mode=no
                // forces the full color-management path. target-prim/target-trc then
                // declare an SDR output so HDR is tone-mapped down to it. Values use
                // libplacebo naming ("bt.709", not "bt709"). This is a no-op for SDR
                // sources (target matches source). Android/iOS are excluded: Android
                // uses vo=gpu-next, which does its own HDR handling.
                handle.option("gpu-dumb-mode", "no")
                handle.option("target-prim", "bt.709")
                handle.option("target-trc", "srgb")
            }

            else -> {}
        }

        handle.option("hwdec", "auto")
        handle.option("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        handle.option("input-default-bindings", "no")
        handle.option("volume-max", "200")

        // Limit demuxer cache since the defaults are too high for mobile devices
        val cacheMegs = if (limitDemuxer()) 32 else 64
        handle.option("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
        handle.option("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")
        // workaround for <https://github.com/mpv-player/mpv/issues/14651>
        handle.option("vd-lavc-film-grain", "cpu")

        handle.initialize()

        handle.option("save-position-on-quit", "no")
        handle.option("force-window", "no")
        handle.option("idle", "yes")
        handle.option("keep-open", "always")

        handle.observeProperty("idle-active", MPVFormat.MPV_FORMAT_FLAG)
        handle.observeProperty("eof-reached", MPVFormat.MPV_FORMAT_FLAG)
        handle.observeProperty("time-pos", MPVFormat.MPV_FORMAT_DOUBLE)
        handle.observeProperty("duration", MPVFormat.MPV_FORMAT_DOUBLE)
        handle.observeProperty("pause", MPVFormat.MPV_FORMAT_FLAG)
        handle.observeProperty("paused-for-cache", MPVFormat.MPV_FORMAT_FLAG)
        handle.observeProperty("seeking", MPVFormat.MPV_FORMAT_FLAG)
        handle.observeProperty("speed", MPVFormat.MPV_FORMAT_DOUBLE)
        handle.observeProperty("volume", MPVFormat.MPV_FORMAT_DOUBLE)
        handle.observeProperty("mute", MPVFormat.MPV_FORMAT_FLAG)
        handle.observeProperty("cache-buffering-state", MPVFormat.MPV_FORMAT_INT64)
        handle.observeProperty("media-title", MPVFormat.MPV_FORMAT_STRING)
        handle.observeProperty("track-list", MPVFormat.MPV_FORMAT_NONE)
        handle.observeProperty("chapter-list", MPVFormat.MPV_FORMAT_NONE)
        handle.observeProperty("hwdec-current", MPVFormat.MPV_FORMAT_NONE)
    }

    @InternalMediampApi
    fun attachRenderSurface(surface: Any): Boolean {
        return attachSurface(handle.ptr, surface)
    }

    @InternalMediampApi
    fun detachRenderSurface(): Boolean {
        return detachSurface(handle.ptr)
    }

    /**
     * Linux overrides this because its GLX context is unavailable before surface attach.
     * `vo=libmpv` requires it before `loadfile`; false defers the load while staying READY.
     */
    protected open fun ensureRenderContextForLoad(): Boolean = true

    /** Linux rejects GLX share-group replacement while this playback session is active. */
    protected fun hasActivePlaybackSession(): Boolean = stateMachine.playbackSessionActive

    /** Completes a Linux load deferred until GLX attach; shares a lock with [resumeImpl]. */
    protected fun renderContextBecameReady() {
        synchronized(pendingLoadLock) {
            val pending = pendingPlaybackLoad ?: return
            pendingPlaybackLoad = null
            if (openResource.value !== pending) return
            loadForPlayback(pending)
        }
    }

    // Clear deferred Linux loads on replace/stop/close so a later GLX attach cannot
    // resurrect a cancelled request.
    private fun cancelPendingPlaybackLoad() {
        synchronized(pendingLoadLock) { pendingPlaybackLoad = null }
    }

    // Shared by the eager path and Linux's deferred path so state transitions stay equal.
    private fun loadForPlayback(media: MPVPlayerData): Boolean {
        handle.setPropertyBoolean("pause", false)
        if (!handle.command("loadfile", media.loadTarget, "replace")) return false
        stateMachine.onPlaybackStarted()
        playbackState.value = PlaybackState.PLAYING
        return true
    }

    override suspend fun setMediaDataImpl(data: MediaData): MPVPlayerData = when (data) {
        is UriMediaData -> {
            // Do not let a later Linux GLX attach load the resource being replaced.
            cancelPendingPlaybackLoad()
            clearPlaybackSession(resetPosition = true)
            handle.command("stop")
            handle.command("playlist-clear")

            val headers = data.headers.toMutableMap()
            headers.remove("User-Agent")?.let { handle.option("user-agent", it) }
            headers.remove("Referer")?.let { handle.option("referrer", it) }
            val headerFields = headers.entries.joinToString(",") { (key, value) -> "$key: $value" }
            handle.option("http-header-fields", headerFields)

            MPVPlayerData(data, data.uri)
        }

        is SeekableInputMediaData -> {
            // Do not let a later Linux GLX attach load the resource being replaced.
            cancelPendingPlaybackLoad()
            clearPlaybackSession(resetPosition = true)
            handle.command("stop")
            handle.command("playlist-clear")

            val loadTarget = buildSeekableInputLoadTarget(data)
            val input = data.createInput(currentCoroutineContext())
            val registeredTarget = try {
                handle.registerSeekableInput(input, loadTarget)
            } catch (t: Throwable) {
                input.close()
                throw t
            }

            SeekableInputPlayerData(data, registeredTarget, handle)
        }
    }

    override fun resumeImpl() {
        when (playbackState.value) {
            PlaybackState.READY -> {
                val media = openResource.value ?: return
                // Atomic with renderContextBecameReady(): GLX may become ready between
                // the check and recording the pending load, which would lose the wake-up.
                synchronized(pendingLoadLock) {
                    if (!ensureRenderContextForLoad()) {
                        pendingPlaybackLoad = media
                        return
                    }
                    pendingPlaybackLoad = null
                    loadForPlayback(media)
                }
            }

            PlaybackState.PAUSED, PlaybackState.PAUSED_BUFFERING -> {
                if (handle.setPropertyBoolean("pause", false)) {
                    stateMachine.onResumed()
                    playbackState.value = PlaybackState.PLAYING
                }
            }

            else -> {}
        }
    }

    override fun pauseImpl() {
        if (handle.setPropertyBoolean("pause", true)) {
            stateMachine.onPauseRequested()
            playbackState.value = PlaybackState.PAUSED
        }
    }

    override fun seekTo(positionMillis: Long) {
        if (playbackState.value < PlaybackState.READY || openResource.value == null) return

        val targetPositionMillis = positionMillis.coerceAtLeast(0L)
        val targetSeconds = targetPositionMillis / 1000.0
        stateMachine.onSeekStarted()
        if (handle.command("seek", formatSeconds(targetSeconds), "absolute+exact")) {
            // Optimistic: keep the intent so rapid skip() calls accumulate correctly and
            // the progress bar never jumps back to a stale pre-seek position.
            _currentPositionMillis.value = targetPositionMillis
        } else {
            stateMachine.onSeekRejected()
        }
    }

    override fun skip(deltaMillis: Long) {
        seekTo(_currentPositionMillis.value + deltaMillis)
    }

    override fun stopPlaybackImpl() {
        // Linux may still have a load waiting for GLX attach.
        cancelPendingPlaybackLoad()
        handle.command("stop")
        clearPlaybackSession(resetPosition = true)
        playbackState.value = PlaybackState.FINISHED
    }

    override fun closeImpl() {
        // Prevent a late Linux GLX attach from loading into a closing player.
        cancelPendingPlaybackLoad()
        (framePreview as? AutoCloseable)?.close()
        clearPlaybackSession(resetPosition = true)
        handle.command("stop")
        handle.destroy()
        handle.close()
        playbackState.value = PlaybackState.DESTROYED
    }

    /**
     * Returns the media currently set via [setMediaData], or `null`.
     * Used by the frame-preview decoder to mirror the main player's media.
     */
    internal fun currentMediaDataOrNull(): MediaData? = openResource.value?.mediaData
}

/**
 * Creates the platform [FramePreview] implementation for this player, or `null` when the
 * platform has no frame-preview support (e.g. Android, which uses ExoPlayer in practice).
 *
 * Called during the player's construction: implementations must only capture references and
 * must not call back into [player] until the first frame request.
 */
internal expect fun createMpvFramePreview(
    player: JvmMpvMediampPlayer,
    context: Any,
    parentCoroutineContext: CoroutineContext,
): FramePreview?

private fun formatSeconds(seconds: Double): String {
    // mpv parses decimal seconds; String.format would be locale-sensitive.
    return ((seconds * 1000).toLong() / 1000.0).toBigDecimal().toPlainString()
}

expect fun limitDemuxer(): Boolean
