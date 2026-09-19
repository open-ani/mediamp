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
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlayerState
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.NetworkStats
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.Screenshots
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.Chapter
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.metadata.Track
import org.openani.mediamp.metadata.TrackGroup
import org.openani.mediamp.metadata.TrackLabel

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

@OptIn(InternalForInheritanceMediampApi::class)
internal class MpvBuffering(state: StateFlow<PlayerState>) : Buffering {
    @Deprecated(
        "Buffering is part of the core state now. Use player.state.map { it.isBuffering }.",
        ReplaceWith("player.state.map { it.isBuffering }"),
    )
    override val isBuffering: Flow<Boolean> =
        state.map { it.isBuffering }.distinctUntilChanged()

    /**
     * mpv's underrun-fill metric ("cache-buffering-state"): counts 0-100 while mpv refills
     * the cache after an underrun and reads 100 during normal playback. It is NOT a
     * buffered-ahead ratio of the whole media.
     */
    override val bufferedPercentage: MutableStateFlow<Int> = MutableStateFlow(0)

    /**
     * End timestamp of the demuxer cache ("demuxer-cache-time"), which is the position up to
     * which data is buffered ahead of the playhead.
     */
    override val bufferedPositionMillis: MutableStateFlow<Long> = MutableStateFlow(Buffering.UNKNOWN_POSITION)

    fun reset() {
        bufferedPercentage.value = 0
        bufferedPositionMillis.value = Buffering.UNKNOWN_POSITION
    }
}

/**
 * Download speed from mpv's "cache-speed" property: bytes per second read from the stream
 * layer into the demuxer cache, averaged over one second.
 *
 * mpv measures it for every stream, including local files, and stops updating it once the
 * cache is filled (the last reading then goes stale). So the value is only published for
 * network media ([isNetworkMedia], decided at open), and reads `0` while "demuxer-cache-idle"
 * says the demuxer is not reading.
 */
@OptIn(InternalForInheritanceMediampApi::class, org.openani.mediamp.ExperimentalMediampApi::class)
internal class MpvNetworkStats : NetworkStats {
    override val downloadSpeedBytesPerSecond: MutableStateFlow<Long> = MutableStateFlow(NetworkStats.UNKNOWN_SPEED)

    /** Whether the open media is loaded over the network. Set at open, before mpv reports. */
    @Volatile
    var isNetworkMedia: Boolean = false

    private var cacheSpeed: Long = 0L
    private var cacheIdle: Boolean = false

    fun onCacheSpeed(bytesPerSecond: Long) {
        cacheSpeed = bytesPerSecond.coerceAtLeast(0L)
        publish()
    }

    fun onCacheIdle(idle: Boolean) {
        cacheIdle = idle
        publish()
    }

    private fun publish() {
        if (!isNetworkMedia) return
        downloadSpeedBytesPerSecond.value = if (cacheIdle) 0L else cacheSpeed
    }

    fun reset() {
        isNetworkMedia = false
        cacheSpeed = 0L
        cacheIdle = false
        downloadSpeedBytesPerSecond.value = NetworkStats.UNKNOWN_SPEED
    }
}

/**
 * Whether mpv will load [uri] through a network protocol rather than from the local file
 * system: a URL scheme other than `file`, excluding Windows drive letters (`C:\...`).
 */
internal fun isNetworkUri(uri: String): Boolean {
    val colon = uri.indexOf(':')
    if (colon <= 1) return false // no scheme, or a drive letter
    val scheme = uri.substring(0, colon)
    if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return false
    return !scheme.equals("file", ignoreCase = true)
}

@OptIn(InternalForInheritanceMediampApi::class)
internal class MpvScreenshots(
    private val takeScreenshotImpl: suspend (path: String) -> Boolean,
) : Screenshots {
    override suspend fun takeScreenshot(destinationFile: String) {
        takeScreenshotImpl(destinationFile)
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
        // Do not write `selected` optimistically here. mpv may reject the selection
        // asynchronously (e.g. no decoder for the track's codec), in which case an
        // optimistic value flashes on and is then reverted by the "track-list" change
        // notification. Native `track-list/*/selected` is the source of truth: a
        // successful property write triggers a "track-list" event and refreshTracks()
        // publishes the confirmed selection (open-ani/animeko#1128).
        return selectTrack(track)
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
