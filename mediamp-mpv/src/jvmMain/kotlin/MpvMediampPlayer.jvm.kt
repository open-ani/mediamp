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

    override val features: PlayerFeatures = buildPlayerFeatures {
        add(PlaybackSpeed.Key, playbackSpeed)
        add(AudioLevelController.Key, audioLevelController)
        add(Buffering.Key, buffering)
        add(Screenshots.Key, screenshots)
        add(VideoAspectRatio.Key, videoAspectRatio)
        add(MediaMetadata, mediaMetadata)
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
                // vo=libmpv defers GL context creation to the render API (the desktop render
                // path is not implemented on Linux yet); ao is picked at playback time.
                handle.option("ao", "pulse,alsa")
                handle.option("vo", "libmpv")
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

    override suspend fun setMediaDataImpl(data: MediaData): MPVPlayerData = when (data) {
        is UriMediaData -> {
            clearPlaybackSession(resetPosition = true)
            handle.command("stop")
            handle.command("playlist-clear")

            val headers = data.headers.toMutableMap()
            headers.remove("User-Agent")?.let { handle.option("user-agent", it) }
            headers.remove("Referer")?.let { handle.option("referrer", it) }
            handle.option("http-header-fields-clr", "")
            headers.forEach { (key, value) ->
                handle.option("http-header-fields-add", "$key: $value")
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
                    stateMachine.onPlaybackStarted()
                    playbackState.value = PlaybackState.PLAYING
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
        handle.command("stop")
        clearPlaybackSession(resetPosition = true)
        playbackState.value = PlaybackState.FINISHED
    }

    override fun closeImpl() {
        clearPlaybackSession(resetPosition = true)
        handle.command("stop")
        handle.destroy()
        handle.close()
        playbackState.value = PlaybackState.DESTROYED
    }
}

private fun formatSeconds(seconds: Double): String {
    // mpv parses decimal seconds; String.format would be locale-sensitive.
    return ((seconds * 1000).toLong() / 1000.0).toBigDecimal().toPlainString()
}

expect fun limitDemuxer(): Boolean
