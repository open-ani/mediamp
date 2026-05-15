/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import kotlinx.browser.document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.features.buildPlayerFeatures
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.Chapter
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.metadata.TrackGroup
import org.openani.mediamp.metadata.TrackLabel
import org.openani.mediamp.metadata.emptyTrackGroup
import org.openani.mediamp.metadata.orEmpty
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import org.w3c.dom.HTMLTrackElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.roundToLong
import kotlin.reflect.KClass

/**
 * Browser-backed MediaMP player for the `wasmJs` target.
 *
 * The implementation uses a native [HTMLVideoElement], so browser media support and CORS rules apply.
 */
@OptIn(
    InternalForInheritanceMediampApi::class,
    InternalMediampApi::class,
    ExperimentalMediampApi::class,
    kotlin.js.ExperimentalWasmJsInterop::class,
)
public class WebMediampPlayer(
    public val videoElement: HTMLVideoElement = document.createElement("video") as HTMLVideoElement,
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
) : AbstractMediampPlayer<WebMediampPlayer.WebData>(Dispatchers.Default + parentCoroutineContext) {
    override val impl: Any get() = videoElement

    override val mediaProperties: MutableStateFlow<MediaProperties?> = MutableStateFlow(null)
    override val currentPositionMillis: MutableStateFlow<Long> = MutableStateFlow(0L)

    private val playbackSpeed = WebPlaybackSpeed()
    private val audioLevelController = WebAudioLevelController()
    private val videoAspectRatio = WebVideoAspectRatio()
    private val mediaMetadata = WebMediaMetadata()
    private val cleanupListeners: List<() -> Unit>

    override val features: PlayerFeatures = buildPlayerFeatures {
        add(PlaybackSpeed, playbackSpeed)
        add(AudioLevelController, audioLevelController)
        add(VideoAspectRatio, videoAspectRatio)
        add(MediaMetadata, mediaMetadata)
    }

    init {
        videoElement.preload = "metadata"
        videoElement.controls = false
        videoElement.playsInline = true
        videoElement.style.width = "100%"
        videoElement.style.height = "100%"
        videoAspectRatio.applyToElement()

        cleanupListeners = listOf(
            videoElement.onEvent("loadedmetadata") {
                mediaProperties.value = MediaProperties(
                    title = currentUriTitle(),
                    durationMillis = videoElement.duration.toMillisOrUnknown(),
                )
                updateCurrentPosition()
            },
            videoElement.onEvent("durationchange") {
                mediaProperties.value = mediaProperties.value.orEmpty().copy(
                    durationMillis = videoElement.duration.toMillisOrUnknown(),
                )
            },
            videoElement.onEvent("timeupdate") { updateCurrentPosition() },
            videoElement.onEvent("seeked") { updateCurrentPosition() },
            videoElement.onEvent("play") { playbackState.value = PlaybackState.PLAYING },
            videoElement.onEvent("playing") { playbackState.value = PlaybackState.PLAYING },
            videoElement.onEvent("pause") {
                if (playbackState.value == PlaybackState.PLAYING || playbackState.value == PlaybackState.PAUSED_BUFFERING) {
                    playbackState.value = PlaybackState.PAUSED
                }
            },
            videoElement.onEvent("waiting") {
                if (playbackState.value == PlaybackState.PLAYING) {
                    playbackState.value = PlaybackState.PAUSED_BUFFERING
                }
            },
            videoElement.onEvent("ended") {
                updateCurrentPosition()
                playbackState.value = PlaybackState.FINISHED
            },
            videoElement.onEvent("error") {
                playbackState.value = PlaybackState.ERROR
            },
        )
    }

    override fun getCurrentMediaProperties(): MediaProperties? = mediaProperties.value

    override fun getCurrentPositionMillis(): Long {
        updateCurrentPosition()
        return currentPositionMillis.value
    }

    override fun getCurrentPlaybackState(): PlaybackState = playbackState.value

    override suspend fun setMediaDataImpl(data: MediaData): WebData {
        val uri = when (data) {
            is UriMediaData -> data.uri
            is SeekableInputMediaData -> data.uri
        }
        if (data is SeekableInputMediaData && !uri.startsWith("http://") && !uri.startsWith("https://") && !uri.startsWith("blob:")) {
            throw UnsupportedOperationException("Browser playback requires a URI that the HTML video element can load: $uri")
        }

        clearTextTracks()
        videoElement.src = uri
        installSubtitleTracks(data)
        videoElement.load()
        updateCurrentPosition()
        mediaProperties.value = MediaProperties(title = currentUriTitle(), durationMillis = videoElement.duration.toMillisOrUnknown())
        return WebData(data)
    }

    override fun seekTo(positionMillis: Long) {
        if (playbackState.value < PlaybackState.READY) return
        videoElement.currentTime = (positionMillis.coerceAtLeast(0L) / 1000.0)
        updateCurrentPosition()
    }

    override fun resumeImpl() {
        runCatching { videoElement.play() }
        playbackState.value = PlaybackState.PLAYING
    }

    override fun pauseImpl() {
        videoElement.pause()
        playbackState.value = PlaybackState.PAUSED
    }

    override fun stopPlaybackImpl() {
        videoElement.pause()
        videoElement.removeAttribute("src")
        clearTextTracks()
        videoElement.load()
        currentPositionMillis.value = 0L
        mediaProperties.value = null
        playbackState.value = PlaybackState.FINISHED
    }

    override fun closeImpl() {
        videoElement.pause()
        cleanupListeners.forEach { it() }
        videoElement.removeAttribute("src")
        clearTextTracks()
        videoElement.load()
        playbackState.value = PlaybackState.DESTROYED
    }

    private fun updateCurrentPosition() {
        currentPositionMillis.value = (videoElement.currentTime * 1000).roundToLong().coerceAtLeast(0L)
    }

    private fun installSubtitleTracks(data: MediaData) {
        data.extraFiles.subtitles.forEach { subtitle ->
            val track = document.createElement("track") as HTMLTrackElement
            track.kind = "subtitles"
            track.src = subtitle.uri
            subtitle.language?.let { track.srclang = it }
            subtitle.label?.let { track.label = it }
            subtitle.mimeType?.let { track.setAttribute("data-mime-type", it) }
            videoElement.appendChild(track)
        }
        mediaMetadata.updateSubtitles(data)
    }

    private fun clearTextTracks() {
        while (videoElement.firstChild != null) {
            videoElement.removeChild(videoElement.firstChild!!)
        }
        mediaMetadata.updateSubtitles(null)
    }

    private fun currentUriTitle(): String? {
        val uri = videoElement.currentSrc.ifBlank { videoElement.src }
        return uri.substringAfterLast('/').substringBefore('?').ifBlank { null }
    }

    public class WebData(
        mediaData: MediaData,
    ) : Data(mediaData)

    public object Factory : MediampPlayerFactory<WebMediampPlayer> {
        override val forClass: KClass<WebMediampPlayer> = WebMediampPlayer::class

        override fun create(context: Any, parentCoroutineContext: CoroutineContext): WebMediampPlayer {
            val element = context as? HTMLVideoElement
            return if (element == null) {
                WebMediampPlayer(parentCoroutineContext = parentCoroutineContext)
            } else {
                WebMediampPlayer(element, parentCoroutineContext)
            }
        }
    }

    private inner class WebPlaybackSpeed : PlaybackSpeed {
        private val state = MutableStateFlow(1f)
        override val valueFlow: Flow<Float> = state
        override val value: Float get() = state.value

        override fun set(speed: Float) {
            val safeSpeed = speed.coerceAtLeast(0.0625f)
            state.value = safeSpeed
            videoElement.playbackRate = safeSpeed.toDouble()
        }
    }

    private inner class WebAudioLevelController : AudioLevelController {
        override val volume: MutableStateFlow<Float> = MutableStateFlow(videoElement.volume.toFloat())
        override val maxVolume: Float = 1f
        override val isMute: MutableStateFlow<Boolean> = MutableStateFlow(videoElement.muted)

        override fun setMute(mute: Boolean) {
            videoElement.muted = mute
            isMute.value = mute
        }

        override fun setVolume(volume: Float) {
            val coerced = volume.coerceIn(0f, maxVolume)
            videoElement.volume = coerced.toDouble()
            this.volume.value = coerced
        }

        override fun volumeUp(value: Float) {
            setVolume(volume.value + value)
        }

        override fun volumeDown(value: Float) {
            setVolume(volume.value - value)
        }
    }

    private inner class WebVideoAspectRatio : VideoAspectRatio {
        override val mode: MutableStateFlow<AspectRatioMode> = MutableStateFlow(AspectRatioMode.FIT)

        override fun setMode(mode: AspectRatioMode) {
            this.mode.value = mode
            applyToElement()
        }

        fun applyToElement() {
            videoElement.style.objectFit = when (mode.value) {
                AspectRatioMode.FIT -> "contain"
                AspectRatioMode.STRETCH -> "fill"
                AspectRatioMode.CROP -> "cover"
            }
        }
    }

    private class WebMediaMetadata : MediaMetadata {
        override val audioTracks: TrackGroup<AudioTrack> = emptyTrackGroup()
        override var subtitleTracks: TrackGroup<SubtitleTrack> = emptyTrackGroup()
            private set
        override val chapters: Flow<List<Chapter>> = emptyFlow()

        fun updateSubtitles(data: MediaData?) {
            subtitleTracks = data?.extraFiles?.subtitles?.mapIndexed { index, subtitle ->
                SubtitleTrack(
                    id = "subtitle-$index",
                    internalId = subtitle.uri,
                    language = subtitle.language,
                    labels = listOfNotNull(subtitle.label?.let { TrackLabel(subtitle.language, it) }),
                )
            }?.let { WebTrackGroup(it) } ?: emptyTrackGroup()
        }
    }
}

@OptIn(InternalForInheritanceMediampApi::class)
private class WebTrackGroup<T>(
    candidates: List<T>,
) : TrackGroup<T> {
    override val selected: MutableStateFlow<T?> = MutableStateFlow(null)
    override val candidates: Flow<List<T>> = MutableStateFlow(candidates)
    private val candidateSet = candidates.toSet()

    override fun select(track: T?): Boolean {
        if (track != null && track !in candidateSet) return false
        selected.value = track
        return true
    }
}

private fun HTMLVideoElement.onEvent(type: String, handler: () -> Unit): () -> Unit {
    val listener: (Event) -> Unit = { handler() }
    addEventListener(type, listener)
    return { removeEventListener(type, listener) }
}

private fun Double.toMillisOrUnknown(): Long {
    return if (isFinite() && this >= 0.0) {
        (this * 1000.0).roundToLong()
    } else {
        -1L
    }
}
