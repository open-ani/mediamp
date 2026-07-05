/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.Screenshots
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.Chapter
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.metadata.Track
import org.openani.mediamp.metadata.TrackGroup
import org.openani.mediamp.metadata.TrackLabel

@OptIn(InternalForInheritanceMediampApi::class)
internal class MpvPlaybackSpeed(private val handle: MPVHandle) : PlaybackSpeed {
    override val valueFlow: MutableStateFlow<Float> = MutableStateFlow(1f)
    override val value: Float get() = valueFlow.value

    override fun set(speed: Float) {
        require(speed > 0f) { "speed must be positive, but was $speed" }
        handle.setPropertyDouble("speed", speed.toDouble())
        valueFlow.value = speed
    }

    /** Called from the mpv event thread on "speed" property change. */
    fun onSpeedChanged(speed: Double) {
        valueFlow.value = speed.toFloat()
    }
}

@OptIn(InternalForInheritanceMediampApi::class)
internal class MpvAudioLevelController(private val handle: MPVHandle) : AudioLevelController {
    override val volume: MutableStateFlow<Float> = MutableStateFlow(1f)
    override val isMute: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val maxVolume: Float = 2f

    override fun setMute(mute: Boolean) {
        handle.setPropertyBoolean("mute", mute)
        isMute.value = mute
    }

    override fun setVolume(volume: Float) {
        val coerced = volume.coerceIn(0f, maxVolume)
        handle.setPropertyDouble("volume", (coerced * 100f).toDouble())
        this.volume.value = coerced
    }

    override fun volumeUp(value: Float) = setVolume(volume.value + value)
    override fun volumeDown(value: Float) = setVolume(volume.value - value)

    fun onVolumeChanged(mpvVolume: Double) {
        volume.value = (mpvVolume / 100.0).toFloat()
    }

    fun onMuteChanged(mute: Boolean) {
        isMute.value = mute
    }
}

@OptIn(InternalForInheritanceMediampApi::class, org.openani.mediamp.ExperimentalMediampApi::class)
internal class MpvBuffering(playbackState: StateFlow<PlaybackState>) : Buffering {
    override val isBuffering: Flow<Boolean> =
        playbackState.map { it == PlaybackState.PAUSED_BUFFERING }.distinctUntilChanged()

    /** 0-100 while mpv is waiting for the cache, updated from "cache-buffering-state". */
    override val bufferedPercentage: MutableStateFlow<Int> = MutableStateFlow(0)
}

@OptIn(InternalForInheritanceMediampApi::class)
internal class MpvScreenshots(private val handle: MPVHandle) : Screenshots {
    override suspend fun takeScreenshot(destinationFile: String) {
        handle.command("screenshot-to-file", destinationFile, "video")
    }
}

@OptIn(InternalForInheritanceMediampApi::class)
internal class MpvVideoAspectRatio(private val handle: MPVHandle) : VideoAspectRatio {
    override val mode: MutableStateFlow<AspectRatioMode> = MutableStateFlow(AspectRatioMode.FIT)

    override fun setMode(mode: AspectRatioMode) {
        // mpv scales/letterboxes inside the render target itself.
        when (mode) {
            AspectRatioMode.FIT -> {
                handle.setPropertyBoolean("keepaspect", true)
                handle.setPropertyDouble("panscan", 0.0)
            }

            AspectRatioMode.CROP -> {
                handle.setPropertyBoolean("keepaspect", true)
                handle.setPropertyDouble("panscan", 1.0)
            }

            AspectRatioMode.STRETCH -> {
                handle.setPropertyBoolean("keepaspect", false)
            }
        }
        this.mode.value = mode
    }
}

@OptIn(InternalForInheritanceMediampApi::class, InternalMediampApi::class)
internal class MpvTrackGroup<T : Track>(
    private val selectTrack: (T?) -> Boolean,
) : TrackGroup<T> {
    override val selected: MutableStateFlow<T?> = MutableStateFlow(null)
    override val candidates: MutableStateFlow<List<T>> = MutableStateFlow(emptyList())

    override fun select(track: T?): Boolean {
        if (!selectTrack(track)) return false
        selected.value = track
        return true
    }

    fun update(tracks: List<T>, selectedTrack: T?) {
        candidates.value = tracks
        selected.value = selectedTrack
    }

    fun clear() {
        candidates.value = emptyList()
        selected.value = null
    }
}

@OptIn(InternalForInheritanceMediampApi::class, InternalMediampApi::class)
internal class MpvMediaMetadata(private val handle: MPVHandle) : MediaMetadata {
    override val audioTracks: MpvTrackGroup<AudioTrack> = MpvTrackGroup { track ->
        handle.setPropertyString("aid", track?.internalId ?: "no")
    }
    override val subtitleTracks: MpvTrackGroup<SubtitleTrack> = MpvTrackGroup { track ->
        handle.setPropertyString("sid", track?.internalId ?: "no")
    }
    override val chapters: MutableStateFlow<List<Chapter>> = MutableStateFlow(emptyList())

    /** Re-reads mpv's "track-list". Called from the mpv event thread on change notification. */
    fun refreshTracks() {
        val count = handle.getPropertyInt("track-list/count")
        val audio = mutableListOf<AudioTrack>()
        val subtitles = mutableListOf<SubtitleTrack>()
        var selectedAudio: AudioTrack? = null
        var selectedSubtitle: SubtitleTrack? = null

        for (i in 0 until count) {
            val type = handle.getPropertyString("track-list/$i/type") ?: continue
            val id = handle.getPropertyInt("track-list/$i/id")
            val title = handle.getPropertyString("track-list/$i/title")
            val lang = handle.getPropertyString("track-list/$i/lang")
            val isSelected = handle.getPropertyBoolean("track-list/$i/selected")
            val label = title ?: lang ?: "#$id"

            when (type) {
                "audio" -> {
                    val track = AudioTrack(
                        id = "audio-$id",
                        internalId = id.toString(),
                        name = title,
                        labels = listOf(TrackLabel(language = null, value = label)),
                    )
                    audio.add(track)
                    if (isSelected) selectedAudio = track
                }

                "sub" -> {
                    val track = SubtitleTrack(
                        id = "sub-$id",
                        internalId = id.toString(),
                        language = lang,
                        labels = listOf(TrackLabel(language = null, value = label)),
                    )
                    subtitles.add(track)
                    if (isSelected) selectedSubtitle = track
                }
            }
        }

        audioTracks.update(audio, selectedAudio)
        subtitleTracks.update(subtitles, selectedSubtitle)
    }

    /** Re-reads mpv's "chapter-list". Called from the mpv event thread on change notification. */
    fun refreshChapters() {
        val count = handle.getPropertyInt("chapter-list/count")
        if (count <= 0) {
            chapters.value = emptyList()
            return
        }
        val durationMillis = (handle.getPropertyDouble("duration") * 1000).toLong()
        val offsets = (0 until count).map { i ->
            val title = handle.getPropertyString("chapter-list/$i/title") ?: "Chapter ${i + 1}"
            val offsetMillis = (handle.getPropertyDouble("chapter-list/$i/time") * 1000).toLong()
            title to offsetMillis
        }
        chapters.value = offsets.mapIndexed { i, (title, offsetMillis) ->
            val endMillis = offsets.getOrNull(i + 1)?.second ?: durationMillis.coerceAtLeast(offsetMillis)
            Chapter(
                name = title,
                durationMillis = (endMillis - offsetMillis).coerceAtLeast(0),
                offsetMillis = offsetMillis,
            )
        }
    }

    fun clear() {
        audioTracks.clear()
        subtitleTracks.clear()
        chapters.value = emptyList()
    }
}
