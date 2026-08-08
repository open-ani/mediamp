@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
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
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import org.w3c.dom.HTMLMediaElement
import org.w3c.dom.HTMLTrackElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.MediaError
import org.w3c.dom.events.Event
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsAny
import kotlin.math.roundToLong
import kotlin.reflect.KClass

/**
 * Browser-backed MediaMP player for the `wasmJs` target.
 *
 * The implementation drives a native [HTMLVideoElement], so browser media support, CORS rules
 * and autoplay policies apply. It reports native facts (transport levels, stalls, seek
 * completions, end-of-media, errors) to the [AbstractMediampPlayer] state machine per
 * `docs/playback-state-v2.md`; it never mutates player state itself.
 *
 * Notable platform behaviors handled here:
 * - The Ready point of an open is the `loadedmetadata` event. If the user agent defers
 *   fetching such that metadata cannot arrive without a user gesture (Data-Saver / Low Power
 *   mode, detected via a zero-byte `suspend` that outlasts a short grace window), the open
 *   completes in degraded mode with [MediampPlayer.mediaProperties] pending.
 * - An autoplay-policy rejection of `play()` is reported as a refused transport observation,
 *   which the machine adopts as an external pause (v1's stranded-PLAYING is unrepresentable).
 * - External pauses (Global Media Controls, Picture-in-Picture, native fullscreen controls)
 *   surface through the element's `pause` event and are adopted by the machine.
 */
@OptIn(
    InternalForInheritanceMediampApi::class,
    InternalMediampApi::class,
    ExperimentalMediampApi::class,
)
public class WebMediampPlayer(
    public val videoElement: HTMLVideoElement = document.createElement("video") as HTMLVideoElement,
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
) : AbstractMediampPlayer(
    parentCoroutineContext = parentCoroutineContext,
    mainDispatcher = Dispatchers.Main,
) {
    override val impl: Any get() = videoElement

    private val audioLevelController = WebAudioLevelController()
    private val videoAspectRatio = WebVideoAspectRatio()
    private val mediaMetadata = WebMediaMetadata()

    override val features: PlayerFeatures = buildPlayerFeatures {
        add(PlaybackSpeed, machinePlaybackSpeed())
        add(AudioLevelController, audioLevelController)
        add(VideoAspectRatio, videoAspectRatio)
        add(MediaMetadata, mediaMetadata)
    }

    /** The session handle native facts are reported to; `null` while no media session is bound. */
    private var activeSessionHandle: PlaybackSessionHandle? = null
    private var sessionListenerRemovers: List<() -> Unit> = emptyList()

    /**
     * Ownership token for the element's media resource: bumped by every `openImpl` `src`
     * assignment and every [unloadElement], so an abandoned open's cancellation cleanup can
     * never reset the element out from under a successor open (single JS thread).
     */
    private var openEpoch: Int = 0

    init {
        videoElement.preload = "metadata"
        videoElement.controls = false
        videoElement.playsInline = true
        videoElement.style.width = "100%"
        videoElement.style.height = "100%"
        videoAspectRatio.applyToElement()
    }

    // region SPI

    override suspend fun openImpl(
        data: MediaData,
        session: PlaybackSessionHandle,
        playWhenReady: Boolean,
        startPositionMillis: Long,
    ): OpenResult {
        val uri = resolveUri(data)

        // Defensive: a superseded session's listeners may still be attached (the machine
        // invalidates the handle, so their facts are dropped, but the DOM listeners leak).
        detachSessionListeners()

        clearTextTracks()
        val epoch = ++openEpoch // this open owns the element until unload or a successor open
        videoElement.preload = "auto"
        videoElement.src = uri
        installSubtitleTracks(data)
        videoElement.load()

        // Ready point (spec §3): 'loadedmetadata', or degraded completion when the UA
        // suspends loading before metadata. A media error fails the open.
        val metadataKnown = try {
            awaitReadyPoint(uri)
        } catch (e: CancellationException) {
            // Abandoned open (stopPlayback during Opening, caller cancellation): the machine
            // cancels the open WITHOUT stopImpl, so unload here or the element keeps fetching
            // the abandoned media while the machine reports Idle. The epoch guard skips the
            // reset when a successor open or unload has already taken ownership.
            if (epoch == openEpoch) unloadElement()
            throw e
        }

        // Apply the start position natively before Ready commits (spec §3). Before metadata
        // (degraded mode) this sets the element's default playback start position.
        val durationMillis = elementDurationMillis()
        val clampedStart = if (durationMillis != null) {
            startPositionMillis.coerceIn(0L, durationMillis)
        } else {
            startPositionMillis.coerceAtLeast(0L)
        }
        if (clampedStart > 0L) {
            videoElement.currentTime = clampedStart / 1000.0
        }

        activeSessionHandle = session
        sessionListenerRemovers = registerSessionListeners(session)

        // Apply the pending intent natively before completing (spec §5 open handoff).
        // play() flips `paused` to false synchronously except when autoplay-blocked, where
        // the promise rejects with `paused` still true and the catch reports a refusal.
        if (playWhenReady) {
            startNativePlay(session)
        }

        return OpenResult(
            sessionResources = null,
            initialSnapshot = TransportSnapshot(
                nativePlayWhenReady = !videoElement.paused,
                isStalled = isStalledNow(),
            ),
            atEnd = videoElement.ended || (durationMillis != null && clampedStart >= durationMillis),
            initialProperties = if (metadataKnown) currentProperties() else null,
        )
    }

    override fun playImpl() {
        val session = activeSessionHandle ?: return
        startNativePlay(session)
        // Read-after-command (spec §5): observe the actual native level synchronously.
        session.reportTransport(transportSnapshotNow())
    }

    override fun pauseImpl() {
        videoElement.pause()
        activeSessionHandle?.reportTransport(transportSnapshotNow())
    }

    override fun seekImpl(positionMillis: Long, seekGeneration: Int) {
        val session = activeSessionHandle ?: return
        if (videoElement.seekable.length == 0) {
            // Unseekable media must never wedge the seek gate (spec §5): synthesize the
            // completion at the actual position.
            session.notifySeekCompleted(seekGeneration, elementPositionMillis(), transportSnapshotNow())
            return
        }
        videoElement.currentTime = positionMillis.coerceAtLeast(0L) / 1000.0
        // Completion arrives via the 'seeked' listener. Rapid seeks coalesce natively (a
        // superseded seek fires no 'seeked'), which latest-generation stamping accounts for.
    }

    override fun setRateImpl(rate: Float) {
        videoElement.playbackRate = rate.toDouble()
    }

    override fun stopImpl() {
        detachSessionListeners()
        unloadElement()
    }

    override fun closeImpl() {
        // Remove all listeners FIRST so queued native dispatches cannot re-enter (v1 property).
        detachSessionListeners()
        unloadElement()
    }

    /** Standard unload idiom: aborts any in-flight fetch and resets the element. */
    private fun unloadElement() {
        openEpoch++ // invalidate any pending abandoned-open cleanup
        videoElement.pause()
        videoElement.removeAttribute("src")
        clearTextTracks()
        videoElement.load()
    }
    // endregion

    // region open helpers

    private fun resolveUri(data: MediaData): String {
        val uri = when (data) {
            is UriMediaData -> data.uri
            is SeekableInputMediaData -> data.uri
        }
        if (data is SeekableInputMediaData &&
            !uri.startsWith("http://") && !uri.startsWith("https://") && !uri.startsWith("blob:")
        ) {
            throw PlaybackException(
                PlaybackErrorCode.UNSUPPORTED_FORMAT,
                "Browser playback requires a URI that the HTML video element can load: $uri",
            )
        }
        return uri
    }

    /**
     * Suspends until the Ready point. Returns `true` when metadata is available, `false` for a
     * degraded completion (UA deferred fetching before metadata; properties arrive later via
     * the session's `loadedmetadata`/`durationchange` listeners). Throws [PlaybackException]
     * when the element reports a media error.
     *
     * Degraded completion (spec §3) requires ALL of: a `suspend` at `NETWORK_IDLE` with
     * `readyState == HAVE_NOTHING`, zero bytes observed for this open (no `progress` event and
     * `buffered` empty), and a grace window in which no normal completion arrives. The HTML
     * resource-fetch algorithm also fires `suspend` at `NETWORK_IDLE` for a fully-fetched fast
     * load and for a mid-load buffer-full suspension — both race `loadedmetadata` and must
     * complete the open normally.
     */
    private suspend fun awaitReadyPoint(uri: String): Boolean = suspendCancellableCoroutine { cont ->
        // Listeners install in the same JS task as load(), so no event of this open precedes them.
        var progressSeen = false
        var graceTimer: Int? = null
        var removers: List<() -> Unit> = emptyList()
        fun cleanup() {
            graceTimer?.let { clearJsTimeout(it) }
            graceTimer = null
            removers.forEach { it() }
        }
        // Genuine UA fetch-deferral (Data-Saver / Low Power): idle with zero bytes observed.
        fun deferredWithZeroBytes(): Boolean =
            videoElement.networkState == HTMLMediaElement.NETWORK_IDLE &&
                videoElement.readyState == HTMLMediaElement.HAVE_NOTHING &&
                !progressSeen &&
                videoElement.buffered.length == 0
        removers = listOf(
            videoElement.onEvent("loadedmetadata") {
                cleanup()
                if (cont.isActive) cont.resume(true)
            },
            videoElement.onEvent("progress") {
                // Bytes arrived: this open cannot be a fetch-deferral. A pending grace timer
                // re-checks at expiry and no-ops.
                progressSeen = true
            },
            videoElement.onEvent("suspend") {
                // Degraded-open candidate. The grace window lets a racing 'loadedmetadata' /
                // 'progress' / readyState advance complete the open normally; only when the
                // zero-byte conditions still hold at expiry does the open complete degraded,
                // with mediaProperties pending.
                if (graceTimer == null && deferredWithZeroBytes()) {
                    graceTimer = setJsTimeout(DEGRADED_OPEN_GRACE_MILLIS) {
                        graceTimer = null
                        if (cont.isActive && deferredWithZeroBytes()) {
                            cleanup()
                            cont.resume(false)
                        }
                    }
                }
            },
            videoElement.onEvent("error") {
                cleanup()
                if (cont.isActive) cont.resumeWithException(mediaErrorToException(videoElement.error, uri))
            },
        )
        cont.invokeOnCancellation { cleanup() }
    }

    private fun startNativePlay(session: PlaybackSessionHandle) {
        videoElement.play().catch<JsAny?> { reason ->
            if (jsErrorName(reason) == "NotAllowedError") {
                // Autoplay policy refused playback: `paused` stayed true. Report a refusal so
                // the machine adopts the external pause instead of retrying (spec §6).
                session.reportTransport(
                    TransportSnapshot(nativePlayWhenReady = false, isStalled = isStalledNow(), refused = true),
                )
            }
            // Other rejections: 'AbortError' means a load()/pause() superseded this play()
            // (already observed via read-after-command reports); fatal failures surface
            // through the element's 'error' event.
            null
        }
    }
    // endregion

    // region native event -> session fact wiring

    private fun registerSessionListeners(session: PlaybackSessionHandle): List<() -> Unit> = listOf(
        videoElement.onEvent("play") { session.reportTransport(transportSnapshotNow()) },
        videoElement.onEvent("playing") { session.reportTransport(transportSnapshotNow()) },
        videoElement.onEvent("pause") {
            // The pre-'ended' pause mandated by HTML is part of the Ended fact and must not
            // be reported as a transport change (spec §5); `ended` is already true then.
            if (!videoElement.ended) {
                session.reportTransport(transportSnapshotNow())
            }
        },
        videoElement.onEvent("ended") { session.notifyEnded() },
        videoElement.onEvent("waiting") {
            session.reportTransport(TransportSnapshot(nativePlayWhenReady = !videoElement.paused, isStalled = true))
        },
        // 'waiting' only fires while potentially playing; paused stalls are evaluated from
        // readyState at the 'seeked'/'canplay'/'loadeddata' edges (spec §6).
        videoElement.onEvent("canplay") { session.reportTransport(transportSnapshotNow()) },
        videoElement.onEvent("loadeddata") { session.reportTransport(transportSnapshotNow()) },
        videoElement.onEvent("seeked") {
            // Latest-generation attribution (spec §5): the browser aborts a superseded seek
            // without firing 'seeked', so this completion closes every generation <= current.
            session.notifySeekCompleted(
                session.currentSeekGeneration,
                elementPositionMillis(),
                transportSnapshotNow(),
            )
            if (videoElement.ended) {
                // Seek-to-end lands at the native end position: report Ended (spec §6).
                session.notifyEnded()
            }
        },
        videoElement.onEvent("timeupdate") { session.notifyPosition(elementPositionMillis()) },
        videoElement.onEvent("durationchange") { session.notifyProperties(currentProperties()) },
        videoElement.onEvent("loadedmetadata") { session.notifyProperties(currentProperties()) },
        videoElement.onEvent("error") { session.notifyError(mediaErrorToException(videoElement.error, videoElement.currentSrc)) },
    )

    private fun detachSessionListeners() {
        sessionListenerRemovers.forEach { it() }
        sessionListenerRemovers = emptyList()
        activeSessionHandle = null
    }

    private fun transportSnapshotNow(): TransportSnapshot = TransportSnapshot(
        nativePlayWhenReady = !videoElement.paused,
        isStalled = isStalledNow(),
    )

    private fun isStalledNow(): Boolean =
        videoElement.readyState < HTMLMediaElement.HAVE_FUTURE_DATA

    private fun elementPositionMillis(): Long =
        (videoElement.currentTime * 1000).roundToLong().coerceAtLeast(0L)

    private fun elementDurationMillis(): Long? = videoElement.duration.let {
        if (it.isFinite() && it >= 0.0) (it * 1000.0).roundToLong() else null // NaN/Infinity = unknown/live
    }

    private fun currentProperties(): MediaProperties = MediaProperties(
        title = currentUriTitle(),
        durationMillis = elementDurationMillis(),
    )

    private fun mediaErrorToException(error: MediaError?, uri: String): PlaybackException {
        val code = when (error?.code) {
            MediaError.MEDIA_ERR_NETWORK -> PlaybackErrorCode.IO
            MediaError.MEDIA_ERR_DECODE -> PlaybackErrorCode.DECODING
            MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED -> PlaybackErrorCode.UNSUPPORTED_FORMAT
            else -> PlaybackErrorCode.INTERNAL
        }
        return PlaybackException(
            code,
            "HTMLMediaElement failed to load or play media (MediaError code=${error?.code}): $uri",
        )
    }
    // endregion

    // region element utilities

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
    // endregion

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

/** Reads `name` from a JS error object; empty string when absent. */
private fun jsErrorName(error: JsAny?): String =
    js("(error && error.name) ? String(error.name) : ''")

/**
 * Grace after a qualifying zero-byte `suspend` before an open completes degraded (spec §3):
 * a racing `loadedmetadata`, `progress`, or readyState advance within this window wins and
 * completes the open normally.
 */
private const val DEGRADED_OPEN_GRACE_MILLIS: Int = 250

/** Schedules [handler] on the browser event loop after [timeoutMillis]; returns a handle for [clearJsTimeout]. */
private fun setJsTimeout(timeoutMillis: Int, handler: () -> Unit): Int =
    js("setTimeout(handler, timeoutMillis)")

private fun clearJsTimeout(handle: Int) {
    js("clearTimeout(handle)")
}
