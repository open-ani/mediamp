/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.buildPlayerFeatures
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.isMuted
import platform.AVFoundation.pause
import platform.AVFoundation.playImmediatelyAtRate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setMuted
import platform.AVFoundation.setRate
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.Foundation.NSKeyValueChangeNewKey
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.NSKeyValueObservingProtocol
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL.Companion.URLWithString
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.darwin.NSObject
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalForInheritanceMediampApi::class, ExperimentalForeignApi::class)
public class AVKitMediampPlayer(
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
) : MediampPlayer {
    override val impl: AVPlayer = AVPlayer()

    // ------------------------------------------------------------------------------------
    // State & Flows
    // ------------------------------------------------------------------------------------
    private val _playbackState = MutableStateFlow(PlaybackState.CREATED)
    override val playbackState: StateFlow<PlaybackState> get() = _playbackState.asStateFlow()

    private val _mediaData = MutableStateFlow<MediaData?>(null)
    override val mediaData: Flow<MediaData?> get() = _mediaData

    private val _mediaProperties = MutableStateFlow<MediaProperties?>(null)
    override val mediaProperties: StateFlow<MediaProperties?> get() = _mediaProperties.asStateFlow()

    private val _currentPositionMillis = MutableStateFlow(0L)
    override val currentPositionMillis: StateFlow<Long> get() = _currentPositionMillis.asStateFlow()

    // A simple derived flow that (naively) calculates progress from [currentPositionMillis / duration].
    override val playbackProgress: Flow<Float> = flow {
        // Re-emit whenever position or total duration changes, by polling.
        // Alternatively, you can combine flows or do a more direct approach.
        while (true) {
            val duration = _mediaProperties.value?.durationMillis?.takeIf { it > 0 }
            val pos = _currentPositionMillis.value
            emit(
                if (duration != null) (pos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                else 0f,
            )
            delay(1.seconds)
        }
    }

    @OptIn(ExperimentalMediampApi::class)
    private val bufferingFeature = object : Buffering {
        override val isBuffering = MutableStateFlow(false)
        override val bufferedPercentage = MutableStateFlow(0)
    }

    /**
     * Implement AudioLevelController feature by bridging to [AVPlayer.volume] and [AVPlayer.isMuted].
     */
    @OptIn(ExperimentalMediampApi::class)
    private val audioLevelController = object : AudioLevelController {
        private val _volume = MutableStateFlow(impl.volume.coerceIn(0f, 1f))
        private val _isMute = MutableStateFlow(impl.isMuted())

        override val volume: StateFlow<Float> get() = _volume
        override val maxVolume: Float = 1.0f
        override val isMute: StateFlow<Boolean> get() = _isMute

        override fun setMute(mute: Boolean) {
            impl.setMuted(mute)
            _isMute.value = mute
        }

        override fun setVolume(volume: Float) {
            val coerced = volume.coerceIn(0f, maxVolume)
            impl.volume = coerced
            // If we're unmuting by setting volume > 0, also ensure isMuted is false.
            if (coerced > 0f && impl.isMuted()) {
                impl.setMuted(false)
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

    @OptIn(ExperimentalMediampApi::class)
    private val playbackSpeedFeature = object : PlaybackSpeed {
        private val _value = MutableStateFlow(
            1.0f, // AVPlayer impl.rate default is 0.0f, so we set 1.0f as default to align with other platforms.
        )

        override val valueFlow: Flow<Float> get() = _value
        override val value: Float get() = _value.value

        override fun set(speed: Float) {
            _value.value = speed
            impl.setRate(speed)
        }
    }

    @OptIn(ExperimentalMediampApi::class)
    override val features: PlayerFeatures = buildPlayerFeatures {
        add(Buffering, bufferingFeature)
        add(AudioLevelController, audioLevelController)
        add(PlaybackSpeed, playbackSpeedFeature)
    }

    // ------------------------------------------------------------------------------------
    // Internal Setup
    // ------------------------------------------------------------------------------------

    private val coroutineScope: CoroutineScope =
        CoroutineScope(Dispatchers.Main + SupervisorJob(parentCoroutineContext[Job]))

    // KVO Observations
    private var timeControlStatusObserver: NSObject? = null
    private var lastPlayerItem: AVPlayerItem? = null
    private var playerItemStatusObserver: NSObject? = null

    private val notificationCenter = NSNotificationCenter.defaultCenter
    private var didPlayToEndObserver: Any? = null

    init {
        // Periodically update current position & buffering progress
        coroutineScope.launch {
            while (currentCoroutineContext().isActive) {
                _currentPositionMillis.value = getCurrentPositionMillis()
                // We do not have an official property for “percentage buffered” from AVPlayer,
                // so here we leave the Buffering feature’s .bufferedPercentage at 0 or 100.
                // You can approximate it using 'loadedTimeRanges' from the AVPlayerItem if you wish.
                delay(200.milliseconds)
            }
        }
        // Observe changes in timeControlStatus -> map to PLAYING, PAUSED, or BUFFERING.
        timeControlStatusObserver = impl.observeValue(
            keyPath = "timeControlStatus",
        ) { _ ->
            updatePlaybackStateFromTimeControlStatus(impl)
        }
    }

    // ------------------------------------------------------------------------------------
    // setMediaData
    // ------------------------------------------------------------------------------------
    @OptIn(ExperimentalMediampApi::class)
    override suspend fun setMediaData(data: MediaData) {
        // If the same data is already set, ignore per the doc
        if (_mediaData.value == data) {
            return
        }
        // Stop/cleanup old resource if any
        stopPlayback() // will set state FINISHED if we were >= READY
        removePlayerItemObservers()

        // open the new data
        // if data.open() throws, we set state=ERROR and rethrow
        try {
            // Create an AVPlayerItem from the given data
            val playerItem = when (data) {
                is UriMediaData -> makePlayerItemFromUriData(data)
                is SeekableInputMediaData -> makePlayerItemFromSeekableData(data)
            }

            // Observe its status
            lastPlayerItem = playerItem
            playerItemStatusObserver = playerItem.observeValue(
                keyPath = "status",
            ) {
                when (impl.status) {
                    AVPlayerItemStatusReadyToPlay -> {
                        // Mark us as READY (if user hasn't called resume() yet, we remain in READY)
                        _playbackState.value = PlaybackState.READY
                        bufferingFeature.isBuffering.value = false
                        // Update mediaProperties if we can parse them now
                        updateMediaProperties(playerItem)
                    }

                    AVPlayerItemStatusFailed -> {
                        // error
                        _playbackState.value = PlaybackState.ERROR
                    }

                    else -> {
                        // .Unknown => do nothing special
                    }
                }
            }

            // Observe "didPlayToEnd" => FINISHED
            didPlayToEndObserver = notificationCenter.addObserverForName(
                name = AVPlayerItemDidPlayToEndTimeNotification,
                `object` = playerItem,
                queue = null,
            ) { _ ->
                _playbackState.value = PlaybackState.FINISHED
            }

            // Attach new item to the player
            impl.replaceCurrentItemWithPlayerItem(playerItem)
            _mediaData.value = data

            // If the doc says setMediaData => we should go to READY eventually:
            // We will wait for the item’s status to become ReadyToPlay, at which point the flow
            // will emit PlaybackState.READY. *If* it fails, we set ERROR.
        } catch (ex: Throwable) {
            // If there's an error opening the data, we set playbackState=ERROR and rethrow
            _playbackState.value = PlaybackState.ERROR
            throw ex
        }
    }

    // ------------------------------------------------------------------------------------
    // Playback control
    // ------------------------------------------------------------------------------------
    override fun resume() {
        /**
         * Follow the doc’s states:
         * If we are at READY or PAUSED => we attempt to play.
         */
        when (val st = _playbackState.value) {
            PlaybackState.READY, PlaybackState.PAUSED -> {
                impl.playImmediatelyAtRate(playbackSpeedFeature.value)
            }

            else -> {
                // do nothing if we're not in READY/PAUSED. The doc says calls are ignored otherwise.
            }
        }
    }

    override fun pause() {
        // If no video is playing, do nothing. Otherwise, just call player.pause()
        if (_mediaData.value == null) return
        impl.pause()
        // We rely on AVPlayer’s KVO to set playbackState -> PAUSED
    }

    override fun stopPlayback() {
        /**
         * If state >= READY => transition to FINISHED.
         * Then detach the item from the player and reset position to 0.
         * So the doc says: "If call stopPlayback() at state < READY => no effect."
         */
        val st = _playbackState.value
        if (st == PlaybackState.READY ||
            st == PlaybackState.PAUSED ||
            st == PlaybackState.PLAYING ||
            st == PlaybackState.PAUSED_BUFFERING
        ) {
            _playbackState.value = PlaybackState.FINISHED
            removePlayerItemObservers()
            impl.replaceCurrentItemWithPlayerItem(null)
            _mediaData.value?.close() // close MediaData
            _mediaData.value = null
            _currentPositionMillis.value = 0
        }
        // else do nothing
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun seekTo(positionMillis: Long) {
        val st = _playbackState.value
        if (st < PlaybackState.READY) {
            // doc says ignore if < READY
            return
        }
        val item = impl.currentItem ?: return
        // Convert from ms to CMTime
        val timeSec = positionMillis.toDouble() / 1000.0
        val cmTime = platform.CoreMedia.CMTimeMakeWithSeconds(timeSec, preferredTimescale = 600)
        item.seekToTime(cmTime)
        _currentPositionMillis.value = positionMillis
    }

    // skip(deltaMillis) has default implementation in the interface => calls seekTo.

    // ------------------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------------------
    override fun getCurrentPlaybackState(): PlaybackState = _playbackState.value

    @OptIn(ExperimentalForeignApi::class)
    override fun getCurrentPositionMillis(): Long {
        val cmTime = impl.currentTime()
        val seconds = cmTime.useContents {
            value.toDouble() / timescale.toDouble()
        }
        return (seconds * 1000).toLong().coerceAtLeast(0)
    }

    override fun getCurrentMediaProperties(): MediaProperties? = _mediaProperties.value

    // ------------------------------------------------------------------------------------
    // Closing
    // ------------------------------------------------------------------------------------
    override fun close() {
        // If already destroyed, do nothing
        if (_playbackState.value == PlaybackState.DESTROYED) return

        // doc says: calling close => state => DESTROYED
        _playbackState.value = PlaybackState.DESTROYED
        stopPlayback() // stop / free any item
        removePlayerItemObservers()

        timeControlStatusObserver?.let {
            impl.removeObserver(it, forKeyPath = "timeControlStatus")
        }
        timeControlStatusObserver = null

        // free the player
        impl.pause()
        // Cancel coroutines
        coroutineScope.cancel()
    }

    // ------------------------------------------------------------------------------------
    // Utility: Observing Player & PlayerItem
    // ------------------------------------------------------------------------------------
    private fun removePlayerItemObservers() {
        playerItemStatusObserver?.let {
            lastPlayerItem?.removeObserver(it, forKeyPath = "status")
        }
        playerItemStatusObserver = null
        lastPlayerItem = null

        didPlayToEndObserver?.let {
            notificationCenter.removeObserver(it)
        }
        didPlayToEndObserver = null
    }

    /**
     * Map AVPlayer.timeControlStatus => [PlaybackState].
     * We rely on the doc approach:
     *  - .Playing => PLAYING
     *  - .Paused => PAUSED (assuming we were at least READY)
     *  - .WaitingToPlayAtSpecifiedRate => BUFFERING
     */
    @OptIn(ExperimentalMediampApi::class)
    private fun updatePlaybackStateFromTimeControlStatus(player: AVPlayer) {
        when (player.timeControlStatus) {
            // 2
            AVPlayerTimeControlStatusPlaying -> {
                // If we haven't reached READY yet, interpret this as we are now PLAYING
                _playbackState.value = PlaybackState.PLAYING
                bufferingFeature.isBuffering.value = false
            }

            // 0
            AVPlayerTimeControlStatusPaused -> {
                // Could be paused or not started. 
                // If we are not yet READY, do not set to PAUSED. 
                if (_playbackState.value >= PlaybackState.READY) {
                    _playbackState.value = PlaybackState.PAUSED
                }
                bufferingFeature.isBuffering.value = false
            }

            // 1
            AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate -> {
                // iOS is waiting => buffering
                if (_playbackState.value >= PlaybackState.READY) {
                    _playbackState.value = PlaybackState.PAUSED_BUFFERING
                    bufferingFeature.isBuffering.value = true
                }
            }

            else -> {
                // Shouldn't happen
            }
        }
    }

    /**
     * Read basic metadata from AVPlayerItem once it's [AVPlayerItemStatusReadyToPlay].
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun updateMediaProperties(playerItem: AVPlayerItem) {
        val durationSec = playerItem.duration.useContents {
            value.toDouble() / timescale.toDouble()
        }
        val ms = (durationSec * 1000).toLong().coerceAtLeast(0)
        _mediaProperties.value = MediaProperties(
            title = null, // Could parse from metadata if you wish
            durationMillis = ms,
        )
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------
    private fun makePlayerItemFromUriData(data: UriMediaData): AVPlayerItem {
        val asset = AVURLAsset(
            URLWithString(data.uri)!!,
            options = mapOf(
                "AVURLAssetHTTPHeaderFieldsKey" to data.headers.toMap(),
            ),
        )
        return AVPlayerItem(asset)
    }

    private fun makePlayerItemFromSeekableData(data: SeekableInputMediaData): AVPlayerItem {
        // On iOS, playing from arbitrary input is more complex than with ExoPlayer. 
        // You must set up a custom AVAssetResourceLoaderDelegate or use a local temp file approach.
        // For demonstration, we just throw NotImplementedError:
        throw UnsupportedOperationException("SeekableInputMediaData is not directly supported in AVKitMediampPlayer yet.")
    }
}

/**
 * Simplified convenience for KVO on a K/N object. You could replace this with your
 * own safer approach, or a well-tested library that provides bridging.
 */
@ExperimentalForeignApi
private inline fun <T : NSObject> T.observeValue(
    keyPath: String,
    crossinline block: (Any) -> Unit
): NSObject {
    val observer = object : NSObject(), NSKeyValueObservingProtocol {

        override fun observeValueForKeyPath(
            keyPath: String?,
            ofObject: Any?,
            change: Map<Any?, *>?,
            context: COpaquePointer?
        ) {
            block(change!![NSKeyValueChangeNewKey]!!)
        }
    }
    this.addObserver(
        observer,
        forKeyPath = keyPath,
        options = NSKeyValueObservingOptionNew,
        context = null,
    )
    return observer
}

///////////////////////////////////////////////////////////////////////////
// Compatibility, remove when the new state design is merged.
///////////////////////////////////////////////////////////////////////////

private val PlaybackState.Companion.CREATED
    get() = PlaybackState.READY

private val PlaybackState.Companion.DESTROYED
    get() = PlaybackState.FINISHED
