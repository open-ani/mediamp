/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.avkit

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.openani.mediamp.AbstractMediampPlayer
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.OpenResult
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.PlaybackSessionHandle
import org.openani.mediamp.TransportSnapshot
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.features.buildPlayerFeatures
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFoundation.AVErrorContentIsNotAuthorized
import platform.AVFoundation.AVErrorContentIsProtected
import platform.AVFoundation.AVErrorDecodeFailed
import platform.AVFoundation.AVErrorDecoderNotFound
import platform.AVFoundation.AVErrorFileFormatNotRecognized
import platform.AVFoundation.AVErrorUndecodableMediaData
import platform.AVFoundation.AVFoundationErrorDomain
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerActionAtItemEndPause
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeErrorKey
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerStatusFailed
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.CMTimeRangeValue
import platform.AVFoundation.actionAtItemEnd
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.isMuted
import platform.AVFoundation.loadedTimeRanges
import platform.AVFoundation.pause
import platform.AVFoundation.playImmediatelyAtRate
import platform.AVFoundation.playbackBufferEmpty
import platform.AVFoundation.playbackLikelyToKeepUp
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setMuted
import platform.AVFoundation.setRate
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSError
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.NSKeyValueObservingProtocol
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSURL.Companion.URLWithString
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSValue
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * AVFoundation-backed MediaMP player for Apple platforms.
 *
 * The implementation drives a native [AVPlayer] and reports native facts (transport levels,
 * stalls, seek completions, end-of-media, errors) to the [AbstractMediampPlayer] state machine
 * per `docs/playback-state-v2.md`; it never mutates player state itself.
 *
 * Notable platform behaviors handled here:
 * - The Ready point of an open is [AVPlayerItem] reaching `ReadyToPlay`; a failed item detaches
 *   itself and fails `setMediaData` with a [PlaybackException] mapped from the `NSError`.
 * - Stalls are observed via `playbackBufferEmpty`/`playbackLikelyToKeepUp` (not
 *   `timeControlStatus`, which conflates the pause cause). `playbackLikelyToKeepUp` is a
 *   heuristic that may stay `false` indefinitely at rate 0, so `isBuffering` in paused states
 *   is best-effort (the `paused-stall` capability is degraded, spec §6).
 * - The end-of-media auto-pause (`actionAtItemEnd = pause`) is part of the Ended fact and is
 *   not reported as a transport change (spec §5).
 * - External pauses (audio-session interruptions, route changes such as unplugging headphones,
 *   Control Center) surface as transport-level observations and are adopted by the machine.
 */
@OptIn(
    InternalMediampApi::class,
    InternalForInheritanceMediampApi::class,
    ExperimentalForeignApi::class,
    ExperimentalMediampApi::class,
)
public class AVKitMediampPlayer(
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
) : AbstractMediampPlayer(
    parentCoroutineContext = parentCoroutineContext,
    mainDispatcher = Dispatchers.Main,
    isOnMainThread = { NSThread.isMainThread() },
) {
    override val impl: AVPlayer = AVPlayer()

    private val notificationCenter = NSNotificationCenter.defaultCenter

    /**
     * The observers of the current media session; `null` while no media is attached.
     * Main-thread confined.
     */
    private var currentAttachment: SessionAttachment? = null

    private val scope: CoroutineScope =
        CoroutineScope(Dispatchers.Main + SupervisorJob(parentCoroutineContext[Job]))

    // region features

    private val bufferingFeature = object : Buffering {
        @Suppress("OVERRIDE_DEPRECATION")
        override val isBuffering: Flow<Boolean> = state.map { it.isBuffering }
        override val bufferedPercentage: MutableStateFlow<Int> = MutableStateFlow(0)
    }

    private val audioLevelController = AVKitAudioLevelController(impl)

    /**
     * Speed goes through the machine (spec §6): while not playing the rate is only stored, and
     * the machine (re)applies it on every transition to playing — setting `AVPlayer.rate`
     * directly while paused would start playback (v1 defect A1).
     */
    private val playbackSpeedFeature = machinePlaybackSpeed()

    private val videoAspectRatioFeature = AVKitVideoAspectRatio()

    override val features: PlayerFeatures = buildPlayerFeatures {
        add(Buffering, bufferingFeature)
        add(AudioLevelController, audioLevelController)
        add(PlaybackSpeed, playbackSpeedFeature)
        add(VideoAspectRatio, videoAspectRatioFeature)
    }
    // endregion

    init {
        // Position/properties/buffered-percentage poll. The machine drops position facts while
        // a seek is in flight, so the optimistic seek position is never overwritten (v1 A7).
        scope.launch {
            while (isActive) {
                currentAttachment?.pollTick()
                delay(POLL_INTERVAL)
            }
        }
    }

    // region SPI

    override suspend fun openImpl(
        data: MediaData,
        session: PlaybackSessionHandle,
        playWhenReady: Boolean,
        startPositionMillis: Long,
    ): OpenResult {
        val playerItem = when (data) {
            is UriMediaData -> makePlayerItem(data)
            is SeekableInputMediaData -> throw UnsupportedOperationException(
                "SeekableInputMediaData is not supported by AVKitMediampPlayer yet.",
            )
        }

        // Defensive: a superseded session's observers may still be attached (the machine
        // invalidates the handle, so their facts are dropped, but the observers linger until
        // their asynchronous release runs).
        currentAttachment?.close()

        impl.replaceCurrentItemWithPlayerItem(playerItem)

        val durationMillis: Long?
        val clampedStart: Long
        try {
            // Ready point (spec §3): item status ReadyToPlay; Failed throws PlaybackException.
            awaitItemReady(playerItem)
            durationMillis = cmTimeToMillisOrNull(playerItem.duration)
            // Apply the start position natively before Ready commits (spec §3); no seek
            // generation is involved.
            clampedStart = if (durationMillis != null) {
                startPositionMillis.coerceIn(0L, durationMillis)
            } else {
                startPositionMillis.coerceAtLeast(0L)
            }
            if (clampedStart > 0L) {
                awaitInitialSeek(clampedStart)
            }
        } catch (e: Throwable) {
            // Open failed or was cancelled: detach the dead item so it cannot linger in the
            // player (v1 defect A8). The status observer is removed by awaitItemReady itself.
            impl.replaceCurrentItemWithPlayerItem(null)
            throw e
        }

        // Attach session observers. No suspension points from here to return, so cancellation
        // cannot leak a live attachment; on post-return supersession the machine closes it via
        // OpenResult.sessionResources.
        val attachment = SessionAttachment(session, playerItem, initialDurationMillis = durationMillis)
        currentAttachment = attachment

        // Apply the pending intent natively before completing (spec §5 open handoff).
        if (playWhenReady) {
            impl.playImmediatelyAtRate(playbackSpeedFeature.value)
        }

        return OpenResult(
            sessionResources = attachment,
            initialSnapshot = transportSnapshotNow(),
            atEnd = durationMillis != null && clampedStart >= durationMillis,
            initialProperties = MediaProperties(title = null, durationMillis = durationMillis),
        )
    }

    override fun playImpl() {
        impl.playImmediatelyAtRate(playbackSpeedFeature.value)
        // Read-after-command (spec §5): every command yields at least one observation.
        currentAttachment?.session?.reportTransport(transportSnapshotNow())
    }

    override fun pauseImpl() {
        impl.pause()
        currentAttachment?.session?.reportTransport(transportSnapshotNow())
    }

    override fun seekImpl(positionMillis: Long, seekGeneration: Int) {
        val session = currentAttachment?.session ?: return
        if (impl.currentItem == null) {
            // Nothing to seek: synthesize the completion so the gate cannot wedge (spec §5).
            session.notifySeekCompleted(seekGeneration, currentNativePositionMillis(), transportSnapshotNow())
            return
        }
        impl.seekToTime(
            time = CMTimeMake(positionMillis, 1000),
            toleranceBefore = CMTimeMake(0, 1000),
            toleranceAfter = CMTimeMake(0, 1000),
        ) { finished ->
            dispatch_async(dispatch_get_main_queue()) {
                onNativeSeekFinished(session, seekGeneration, finished)
            }
        }
    }

    override fun setRateImpl(rate: Float) {
        // The machine calls this only while playing (spec §6), so setting the rate never
        // starts paused playback (v1 defect A1).
        impl.setRate(rate)
    }

    override fun stopImpl() {
        currentAttachment?.close()
        impl.pause()
        impl.replaceCurrentItemWithPlayerItem(null)
        bufferingFeature.bufferedPercentage.value = 0
    }

    override fun closeImpl() {
        // Released has already been emitted and the session invalidated, so teardown-induced
        // native pauses cannot re-enter the state (v1 defect A4).
        currentAttachment?.close()
        impl.pause()
        impl.replaceCurrentItemWithPlayerItem(null)
        scope.cancel()
    }
    // endregion

    // region native signal handling

    private fun onNativeSeekFinished(session: PlaybackSessionHandle, issuedGeneration: Int, finished: Boolean) {
        // Latest-generation attribution (spec §5): a completion closes every generation <= it.
        val latestGeneration = session.currentSeekGeneration
        if (!finished && latestGeneration > issuedGeneration) {
            // Interrupted by a newer machine-issued seek; that seek's completion closes the gate.
            return
        }
        // Either the seek landed, or the native side refused it with no superseding machine
        // seek (unseekable/live media): complete at the actual position — every issued seekImpl
        // yields exactly one completion (spec §5).
        val positionMillis = currentNativePositionMillis()
        session.notifySeekCompleted(latestGeneration, positionMillis, transportSnapshotNow())

        // Ended-on-seek normalization (spec §6): a completion landing at the native end
        // position, with known duration, reports Ended.
        val durationMillis = currentItemDurationMillis()
        if (durationMillis != null && positionMillis >= durationMillis - END_TOLERANCE_MILLIS) {
            session.notifyEnded()
        }
    }

    /**
     * Reports the current transport level, unless the observation is the platform's
     * end-of-media auto-pause — that is part of the Ended fact, not a transport change
     * (spec §5); reporting it would masquerade as an external pause racing `notifyEnded`.
     */
    private fun reportTransportLevel(session: PlaybackSessionHandle) {
        if (impl.timeControlStatus == AVPlayerTimeControlStatusPaused && isEndOfMediaAutoPause()) {
            return
        }
        session.reportTransport(transportSnapshotNow())
    }

    private fun isEndOfMediaAutoPause(): Boolean {
        if (impl.actionAtItemEnd != AVPlayerActionAtItemEndPause) return false
        val durationMillis = currentItemDurationMillis() ?: return false
        return currentNativePositionMillis() >= (durationMillis - END_TOLERANCE_MILLIS).coerceAtLeast(0L)
    }

    private fun transportSnapshotNow(): TransportSnapshot {
        val timeControlStatus = impl.timeControlStatus
        val item = impl.currentItem
        val bufferEmpty = item?.playbackBufferEmpty ?: false
        val likelyToKeepUp = item?.playbackLikelyToKeepUp ?: true
        return TransportSnapshot(
            nativePlayWhenReady = timeControlStatus != AVPlayerTimeControlStatusPaused,
            // Buffering derives from the buffer properties, not timeControlStatus (v1 A2):
            // a stall while the user intends to play is never reported as not-buffering.
            isStalled = bufferEmpty ||
                    (!likelyToKeepUp && timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate),
        )
    }
    // endregion

    // region open helpers

    private fun makePlayerItem(data: UriMediaData): AVPlayerItem {
        val uri = data.uri
        val asset = if (uri.startsWith("file://")) {
            // For local file URIs, use fileURLWithPath for reliable file URL construction
            // and do not pass AVURLAssetHTTPHeaderFieldsKey which is only for HTTP(S).
            val path = URLWithString(uri)?.path
                ?: throw PlaybackException(PlaybackErrorCode.IO, "Invalid file URI: $uri")
            AVURLAsset(NSURL.fileURLWithPath(path), null)
        } else {
            AVURLAsset(
                URLWithString(uri) ?: throw PlaybackException(PlaybackErrorCode.IO, "Invalid URI: $uri"),
                if (data.headers.isEmpty()) null else mapOf("AVURLAssetHTTPHeaderFieldsKey" to data.headers.toMap()),
            )
        }
        return AVPlayerItem(asset)
    }

    /**
     * Suspends until [item] reaches `ReadyToPlay`. Throws a [PlaybackException] mapped from the
     * item's `NSError` on `Failed`. The status observer is removed on every exit path,
     * including cancellation.
     */
    private suspend fun awaitItemReady(item: AVPlayerItem) {
        suspendCancellableCoroutine { cont ->
            var observer: NSObject? = null
            var done = false // main-thread confined

            fun finish(action: () -> Unit) {
                if (done) return
                done = true
                observer?.let { item.removeObserver(it, KEY_STATUS) }
                observer = null
                action()
            }

            fun checkStatus() {
                when (item.status) {
                    AVPlayerItemStatusReadyToPlay -> finish {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    AVPlayerItemStatusFailed -> finish {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                item.error.toPlaybackException("AVPlayerItem failed to load media"),
                            )
                        }
                    }

                    else -> {}
                }
            }

            observer = item.observeKeyPathOnMain(KEY_STATUS) { checkStatus() }
            cont.invokeOnCancellation {
                dispatch_async(dispatch_get_main_queue()) { finish {} }
            }
            checkStatus()
        }
    }

    /**
     * Applies the start position as part of the open (spec §3): a plain native seek awaited to
     * completion, involving no seek generation. A refused seek (unseekable media) proceeds
     * without failing the open.
     */
    private suspend fun awaitInitialSeek(positionMillis: Long) {
        suspendCancellableCoroutine { cont ->
            impl.seekToTime(
                time = CMTimeMake(positionMillis, 1000),
                toleranceBefore = CMTimeMake(0, 1000),
                toleranceAfter = CMTimeMake(0, 1000),
            ) { _ ->
                dispatch_async(dispatch_get_main_queue()) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }
    // endregion

    // region session attachment

    /**
     * All native observers of one media session. Reports route to [session]; the machine drops
     * facts of invalidated sessions, so no handler needs staleness checks beyond [detached].
     *
     * Detached by [stopImpl]/[closeImpl] on the main thread, or by the machine closing
     * [OpenResult.sessionResources] (idempotent, trampolines to the main thread).
     */
    private inner class SessionAttachment(
        val session: PlaybackSessionHandle,
        private val item: AVPlayerItem,
        initialDurationMillis: Long?,
    ) : AutoCloseable {
        /** Main-thread confined; flipped exactly once by [detachOnMainThread]. */
        private var detached = false
        private var lastNotifiedDurationMillis: Long? = initialDurationMillis

        // All handlers run on the main thread and only report facts / read native levels
        // (v1 defect A3: no state is mutated from observer threads).

        private val timeControlStatusObserver = impl.observeKeyPathOnMain(KEY_TIME_CONTROL_STATUS) {
            if (!detached) reportTransportLevel(session)
        }

        private val playerStatusObserver = impl.observeKeyPathOnMain(KEY_STATUS) {
            if (!detached && impl.status == AVPlayerStatusFailed) {
                session.notifyError(impl.error.toPlaybackException("AVPlayer entered the failed state"))
            }
        }

        private val itemStatusObserver = item.observeKeyPathOnMain(KEY_STATUS) {
            if (!detached && item.status == AVPlayerItemStatusFailed) {
                session.notifyError(item.error.toPlaybackException("AVPlayerItem failed during playback"))
            }
        }

        private val bufferEmptyObserver = item.observeKeyPathOnMain(KEY_PLAYBACK_BUFFER_EMPTY) {
            if (!detached) reportTransportLevel(session)
        }

        private val likelyToKeepUpObserver = item.observeKeyPathOnMain(KEY_PLAYBACK_LIKELY_TO_KEEP_UP) {
            if (!detached) reportTransportLevel(session)
        }

        private val didPlayToEndToken = notificationCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            // The machine guards status (Ended cannot overwrite Error — v1 defect A6) and
            // drops stale end facts while a seek is in flight.
            session.notifyEnded()
        }

        private val failedToPlayToEndToken = notificationCenter.addObserverForName(
            name = AVPlayerItemFailedToPlayToEndTimeNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { notification ->
            val nsError = notification?.userInfo?.get(AVPlayerItemFailedToPlayToEndTimeErrorKey) as? NSError
            session.notifyError(nsError.toPlaybackException("Playback failed before reaching the end of the media"))
        }

        private val interruptionToken = notificationCenter.addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            // The system pauses the AVPlayer on interruptions (phone call, Siri): report the
            // observed level so the machine adopts the external pause (spec §6).
            if (!detached) reportTransportLevel(session)
        }

        private val routeChangeToken = notificationCenter.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            if (!detached) reportTransportLevel(session)
        }

        /** Periodic tick on the main thread: position, duration changes, buffered percentage. */
        fun pollTick() {
            if (detached) return
            session.notifyPosition(currentNativePositionMillis())
            val durationMillis = currentItemDurationMillis()
            if (durationMillis != lastNotifiedDurationMillis) {
                lastNotifiedDurationMillis = durationMillis
                session.notifyProperties(MediaProperties(title = null, durationMillis = durationMillis))
            }
            bufferingFeature.bufferedPercentage.value = computeBufferedPercentage(durationMillis)
        }

        private fun detachOnMainThread() {
            if (detached) return
            detached = true
            item.removeObserver(itemStatusObserver, KEY_STATUS)
            item.removeObserver(bufferEmptyObserver, KEY_PLAYBACK_BUFFER_EMPTY)
            item.removeObserver(likelyToKeepUpObserver, KEY_PLAYBACK_LIKELY_TO_KEEP_UP)
            impl.removeObserver(timeControlStatusObserver, KEY_TIME_CONTROL_STATUS)
            impl.removeObserver(playerStatusObserver, KEY_STATUS)
            notificationCenter.removeObserver(didPlayToEndToken)
            notificationCenter.removeObserver(failedToPlayToEndToken)
            notificationCenter.removeObserver(interruptionToken)
            notificationCenter.removeObserver(routeChangeToken)
            if (currentAttachment === this) {
                currentAttachment = null
            }
        }

        override fun close() {
            if (NSThread.isMainThread()) {
                detachOnMainThread()
            } else {
                dispatch_async(dispatch_get_main_queue()) { detachOnMainThread() }
            }
        }
    }
    // endregion

    // region time helpers

    private fun currentNativePositionMillis(): Long =
        cmTimeToMillisOrNull(impl.currentTime()) ?: 0L

    private fun currentItemDurationMillis(): Long? =
        impl.currentItem?.let { cmTimeToMillisOrNull(it.duration) }

    /**
     * Computes how far ahead of the playhead contiguous data is buffered, as a percentage of
     * the total duration (v1: permanently 0).
     */
    private fun computeBufferedPercentage(durationMillis: Long?): Int {
        if (durationMillis == null || durationMillis <= 0L) return 0
        val item = impl.currentItem ?: return 0
        var reachableEndMillis = currentNativePositionMillis()
        for (rangeValue in item.loadedTimeRanges) {
            val range = (rangeValue as? NSValue)?.CMTimeRangeValue ?: continue
            range.useContents {
                val startMillis = if (start.timescale != 0) start.value * 1000L / start.timescale else 0L
                val rangeDurationMillis = if (duration.timescale != 0) duration.value * 1000L / duration.timescale else 0L
                val endMillis = startMillis + rangeDurationMillis
                if (startMillis <= reachableEndMillis + RANGE_CONTIGUITY_SLACK_MILLIS && endMillis > reachableEndMillis) {
                    reachableEndMillis = endMillis
                }
            }
        }
        return (reachableEndMillis * 100L / durationMillis).toInt().coerceIn(0, 100)
    }
    // endregion

    private companion object {
        const val KEY_STATUS = "status"
        const val KEY_TIME_CONTROL_STATUS = "timeControlStatus"
        const val KEY_PLAYBACK_BUFFER_EMPTY = "playbackBufferEmpty"
        const val KEY_PLAYBACK_LIKELY_TO_KEEP_UP = "playbackLikelyToKeepUp"

        /** Tolerance for "the playhead is at the end of the media" (spec §6). */
        const val END_TOLERANCE_MILLIS = 100L

        /** Gap between loaded ranges still considered contiguous for buffered-percentage. */
        const val RANGE_CONTIGUITY_SLACK_MILLIS = 500L

        val POLL_INTERVAL = 200.milliseconds
    }
}

/** Converts a CMTime to milliseconds; `null` for invalid/indefinite/negative (unknown/live). */
@OptIn(ExperimentalForeignApi::class)
private fun cmTimeToMillisOrNull(time: kotlinx.cinterop.CValue<platform.CoreMedia.CMTime>): Long? {
    val seconds = CMTimeGetSeconds(time)
    if (seconds.isNaN() || seconds.isInfinite() || seconds < 0.0) return null
    return (seconds * 1000).roundToLong()
}

/** Maps an `NSError` from AVFoundation to a [PlaybackException] (spec §7). */
private fun NSError?.toPlaybackException(message: String): PlaybackException {
    val code = when {
        this == null -> PlaybackErrorCode.INTERNAL
        domain == NSURLErrorDomain -> PlaybackErrorCode.IO
        domain == AVFoundationErrorDomain -> when (this.code) {
            AVErrorFileFormatNotRecognized,
            AVErrorDecoderNotFound,
            AVErrorUndecodableMediaData,
                -> PlaybackErrorCode.UNSUPPORTED_FORMAT

            AVErrorContentIsProtected,
            AVErrorContentIsNotAuthorized,
                -> PlaybackErrorCode.ACCESS_DENIED

            AVErrorDecodeFailed -> PlaybackErrorCode.DECODING

            else -> PlaybackErrorCode.INTERNAL
        }

        else -> PlaybackErrorCode.INTERNAL
    }
    val detail = if (this == null) {
        ""
    } else {
        " (domain=$domain, code=${this.code}${localizedDescription.let { ", $it" }})"
    }
    return PlaybackException(code, "$message$detail")
}

@OptIn(InternalForInheritanceMediampApi::class)
internal class AVKitAudioLevelController(
    private val player: AVPlayer,
) : AudioLevelController {
    private val _volume = MutableStateFlow(player.volume.coerceIn(0f, 1f))
    private val _isMute = MutableStateFlow(player.isMuted())

    override val volume: StateFlow<Float> get() = _volume
    override val maxVolume: Float = 1.0f
    override val isMute: StateFlow<Boolean> get() = _isMute

    override fun setMute(mute: Boolean) {
        player.setMuted(mute)
        _isMute.value = mute
    }

    override fun setVolume(volume: Float) {
        val coerced = volume.coerceIn(0f, maxVolume)
        player.volume = coerced
        // If we're unmuting by setting volume > 0, also ensure isMuted is false.
        if (coerced > 0f && player.isMuted()) {
            player.setMuted(false)
            _isMute.value = false
        }
        _volume.value = coerced
    }

    override fun volumeUp(value: Float) {
        setVolume(_volume.value + value)
    }

    override fun volumeDown(value: Float) {
        setVolume(_volume.value - value)
    }
}

@OptIn(InternalForInheritanceMediampApi::class)
internal class AVKitVideoAspectRatio : VideoAspectRatio {
    private val _mode = MutableStateFlow(AspectRatioMode.FIT)

    override val mode: StateFlow<AspectRatioMode> get() = _mode

    override fun setMode(mode: AspectRatioMode) {
        _mode.value = mode
    }
}

/**
 * A KVO observer that trampolines change callbacks to the main queue. KVO may deliver on an
 * arbitrary thread; handlers re-read the current native levels on the main thread, so the
 * change payload is intentionally ignored (level-triggered reporting, spec §5).
 */
@OptIn(ExperimentalForeignApi::class)
private class MainQueueKvoObserver(
    private val onChange: () -> Unit,
) : NSObject(), NSKeyValueObservingProtocol {
    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: COpaquePointer?,
    ) {
        dispatch_async(dispatch_get_main_queue()) { onChange() }
    }
}

/** Registers [onChange] for KVO changes of [keyPath], dispatched to the main queue. */
@OptIn(ExperimentalForeignApi::class)
private fun NSObject.observeKeyPathOnMain(keyPath: String, onChange: () -> Unit): NSObject {
    val observer = MainQueueKvoObserver(onChange)
    addObserver(
        observer,
        forKeyPath = keyPath,
        options = NSKeyValueObservingOptionNew,
        context = null,
    )
    return observer
}
