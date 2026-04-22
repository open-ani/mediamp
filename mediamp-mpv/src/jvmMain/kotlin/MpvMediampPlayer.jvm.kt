/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.openani.mediamp.AbstractMediampPlayer
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.features.PlayerFeatures
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

    private val handle by lazy { MPVHandle(context) }
    private var pauseRequestedByUser: Boolean = false
    private var pausedForCache: Boolean = false
    private var playbackSessionActive: Boolean = false

    var currentSize: Size? = null
        @InternalMediampApi set

    private val eventListener = object : EventListener {
        override fun onPropertyChange(name: String) {

        }

        override fun onPropertyChange(name: String, value: Boolean) {
            when (name) {
                "pause" -> {
                    pauseRequestedByUser = value
                    syncObservedPlaybackState()
                }

                "paused-for-cache" -> {
                    pausedForCache = value
                    syncObservedPlaybackState()
                }

                "idle-active" -> {
                    if (value && playbackSessionActive && playbackState.value >= PlaybackState.READY) {
                        resetPlaybackSessionFlags()
                        playbackStateDelegate.value = PlaybackState.FINISHED
                    }
                }

            }
        }

        override fun onPropertyChange(name: String, value: Long) {
            when (name) {
                "time-pos/full" -> _currentPositionMillis.value = value * 1000
                "duration/full" -> _mediaProperties.value =
                    if (mediaProperties.value == null) MediaProperties(null, value * 1000)
                    else mediaProperties.value?.copy(durationMillis = value * 1000)
            }
        }

        override fun onPropertyChange(name: String, value: Double) {
        }

        override fun onPropertyChange(name: String, value: String) {
            when (name) {
                "media-title" -> _mediaProperties.value =
                    if (mediaProperties.value == null) MediaProperties(value, -1)
                    else mediaProperties.value?.copy(title = value)
            }
        }

    }

    override val impl: Any get() = handle

    private val _currentPositionMillis: MutableStateFlow<Long> = MutableStateFlow(0L)
    override val currentPositionMillis: StateFlow<Long> = _currentPositionMillis

    private val _mediaProperties: MutableStateFlow<MediaProperties?> = MutableStateFlow(null)
    override val mediaProperties: StateFlow<MediaProperties?> = _mediaProperties

    override val features: PlayerFeatures = buildPlayerFeatures { }

    override fun getCurrentMediaProperties(): MediaProperties? {
        return mediaProperties.value
    }

    override fun getCurrentPlaybackState(): PlaybackState {
        return playbackState.value
    }

    override fun getCurrentPositionMillis(): Long {
        return currentPositionMillis.value
    }

    private fun syncObservedPlaybackState() {
        if (!playbackSessionActive || playbackState.value < PlaybackState.READY) {
            return
        }
        playbackStateDelegate.value = when {
            pauseRequestedByUser -> PlaybackState.PAUSED
            pausedForCache -> PlaybackState.PAUSED_BUFFERING
            else -> PlaybackState.PLAYING
        }
    }

    private fun resetPlaybackSessionFlags() {
        pauseRequestedByUser = false
        pausedForCache = false
        playbackSessionActive = false
    }

    private fun clearPlayerTransientState(resetPosition: Boolean) {
        resetPlaybackSessionFlags()
        _mediaProperties.value = null
        if (resetPosition) {
            _currentPositionMillis.value = 0L
        }
    }

    private fun clearPlaybackSession(resetPosition: Boolean) {
        clearPlayerTransientState(resetPosition = resetPosition)
    }

    @InternalMediampApi
    fun createRenderContext(devicePtr: Long, contextPtr: Long): Boolean {
        return createRenderContext(handle.ptr, devicePtr, contextPtr)
    }

    @InternalMediampApi
    fun releaseRenderContext(): Boolean {
        return destroyRenderContext(handle.ptr)
    }

    @InternalMediampApi
    fun createTexture(width: Int, height: Int): Int {
        return createTexture(handle.ptr, width, height)
    }

    @InternalMediampApi
    fun releaseTexture(): Boolean {
        return releaseTexture(handle.ptr)
    }

    @InternalMediampApi
    fun renderFrame(): Boolean {
        return renderFrameToTexture(handle.ptr)
    }

    internal fun setRenderUpdateListener(listener: RenderUpdateListener?): Boolean {
        return handle.setRenderUpdateListener(listener)
    }

    init {
        handle.setEventListener(eventListener)

        handle.option("config", "no")
        // handle.option("config-dir", File(filesDir, "mpv_config").absolutePath)
        // handle.option("gpu-shader-cache-dir", File(cacheDir, "mpv_gpu_cache").absolutePath)
        // handle.option("icc-cache-dir", File(cacheDir, "mpv_icc_cache").absolutePath)
        handle.option("profile", "fast")

        when (currentPlatform()) {
            is Platform.Android -> {
                handle.option("gpu-context", "android")
                handle.option("opengl-es", "yes")
                handle.option("ao", "audiotrack,opensles")
                handle.option("vo", "gpu-next")
            }

            is Platform.Windows -> {
                handle.option("gpu-context", "win,opengl")
                handle.option("opengl-es", "no")

                handle.option("ao", "wasapi")
                handle.option("vo", "libmpv")
                handle.option("fbo-format", "rgba8")
                handle.option("dither-depth", "no")
                handle.option("video-sync", "audio")
                handle.option("video-timing-offset", "0.0")
            }

            is Platform.MacOS -> {
                handle.option("gpu-context", "macvk")

                handle.option("ao", "avfoundation")
                handle.option("vo", "libmpv")
            }

            else -> {}
        }


        handle.option("hwdec", "auto")
        handle.option("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        // handle.option("tls-verify", "yes")
        // handle.option("tls-ca-file", "${this.context.filesDir.path}/cacert.pem")
        handle.option("input-default-bindings", "yes")

        // Limit demuxer cache since the defaults are too high for mobile devices   
        val cacheMegs = if (limitDemuxer()) 32 else 64
        handle.option("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
        handle.option("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")
        // screenshot
        // handle.option("screenshot-directory", screenshotDir.path)
        // workaround for <https://github.com/mpv-player/mpv/issues/14651>
        handle.option("vd-lavc-film-grain", "cpu")

        handle.initialize()

        handle.option("save-position-on-quit", "no")
        handle.option("force-window", "no")
        handle.option("idle", "yes")
        handle.option("keep-open", "always")

        handle.observeProperty("idle-active", MPVFormat.MPV_FORMAT_FLAG)
        handle.observeProperty("time-pos/full", MPVFormat.MPV_FORMAT_INT64)
        handle.observeProperty("duration/full", MPVFormat.MPV_FORMAT_INT64)
        handle.observeProperty("pause", MPVFormat.MPV_FORMAT_FLAG)
        handle.observeProperty("paused-for-cache", MPVFormat.MPV_FORMAT_FLAG)
        handle.observeProperty("speed", MPVFormat.MPV_FORMAT_STRING) // todo

        handle.observeProperty("media-title", MPVFormat.MPV_FORMAT_STRING) // to
        handle.observeProperty("metadata", MPVFormat.MPV_FORMAT_NONE)
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

    override suspend fun setMediaDataImpl(data: MediaData): MPVPlayerData = when (data) {
        is UriMediaData -> {
            clearPlaybackSession(resetPosition = true)
            val headers = data.headers

            // 清除播放列表
            handle.command("stop")
            handle.command("playlist-clear")
            // 设置 headers 和 ua
            handle.option(
                "user-agent",
                headers["User-Agent"]
                    ?: """Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3""",
            )
            handle.option("http-header-fields-clr", "")
            headers.forEach { (key, value) ->
                handle.option("http-header-fields", "$key: $value")
            }

            MPVPlayerData(data, data.uri)
        }

        is SeekableInputMediaData -> {
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
                handle.setPropertyBoolean("pause", false)
                if (handle.command("loadfile", media.loadTarget, "replace")) {
                    playbackSessionActive = true
                    pauseRequestedByUser = false
                    pausedForCache = false
                    playbackStateDelegate.value = PlaybackState.PLAYING
                }
            }

            PlaybackState.PAUSED, PlaybackState.PAUSED_BUFFERING -> {
                if (handle.setPropertyBoolean("pause", false)) {
                    playbackSessionActive = true
                    pauseRequestedByUser = false
                    playbackStateDelegate.value = PlaybackState.PLAYING
                }
            }

            else -> {} // TODO: unreachable
        }
    }

    override fun pauseImpl() {
        if (handle.setPropertyBoolean("pause", true)) {
            playbackSessionActive = true
            pauseRequestedByUser = true
            playbackStateDelegate.value = PlaybackState.PAUSED
        }
    }

    override fun seekTo(positionMillis: Long) {
        if (playbackState.value < PlaybackState.READY || openResource.value == null) return

        val targetPositionMillis = positionMillis.coerceAtLeast(0L)
        if (handle.command("seek", (targetPositionMillis / 1000L).toString(), "absolute+exact")) {
            _currentPositionMillis.value = targetPositionMillis
        }
    }

    override fun skip(deltaMillis: Long) {
        if (playbackState.value < PlaybackState.READY || openResource.value == null) return

        if (handle.command("seek", (deltaMillis / 1000L).toString(), "relative+exact")) {
            _currentPositionMillis.value = (_currentPositionMillis.value + deltaMillis).coerceAtLeast(0L)
        }
    }

    override fun stopPlaybackImpl() {
        handle.command("stop")
        clearPlaybackSession(resetPosition = true)
        playbackStateDelegate.value = PlaybackState.FINISHED
    }


    override fun closeImpl() {
        clearPlaybackSession(resetPosition = true)
        handle.command("stop")
        handle.destroy()
        handle.close()
        playbackStateDelegate.value = PlaybackState.DESTROYED
    }
}

expect fun limitDemuxer(): Boolean
