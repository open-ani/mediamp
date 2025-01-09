/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openani.mediamp.source.MediaData
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Default abstract implementation of [MediampPlayer].
 * 
 * Method [setMediaData], [resume], [pause], [stopPlayback] and [close] are wrapped, 
 * please implement the actual playback control logic in corresponding `xxxImpl` methods. Note that:
 *
 * - You should not change playback state in the `xxxImpl` methods, the state is managed by this class.
 * - You can ignore error handling in the `xxxImpl` methods, this class will handle it.
 * - `xxxImpl` methods will only be called when the playback state is valid at its state transformation path, 
 * so it is not necessary to validate playback state.
 * - `xxxImpl` methods call synchronously, new state will be set after the method returns.
 * If your player core only provides asynchronous methods, you should block until your core callback is executed.
 * 
 */
@InternalMediampApi
@OptIn(InternalForInheritanceMediampApi::class)
public abstract class AbstractMediampPlayer<D : AbstractMediampPlayer.Data>(
    parentCoroutineContext: CoroutineContext,
) : MediampPlayer {
    protected val backgroundScope: CoroutineScope = CoroutineScope(
        parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job.Key]),
    ).apply {
        coroutineContext.job.invokeOnCompletion {
            close()
        }
    }

    override val playbackState: MutableStateFlow<PlaybackState> = MutableStateFlow(PlaybackState.CREATED)

    /**
     * Currently playing resource that should be closed when the controller is closed.
     * @see setMediaData
     */
    protected val openResource: MutableStateFlow<D?> = MutableStateFlow(null)

    public open class Data(
        public open val mediaData: MediaData,
        public open val releaseResource: () -> Unit,
    )

    final override val mediaData: Flow<MediaData?> = openResource.map { it?.mediaData }

    final override val playbackProgress: Flow<Float>
        get() = combine(mediaProperties.filterNotNull(), currentPositionMillis) { properties, duration ->
            if (properties.durationMillis == 0L) {
                return@combine 0f
            }
            (duration / properties.durationMillis).toFloat().coerceIn(0f, 1f)
        }

    private val setVideoSourceMutex = Mutex()

    final override suspend fun setMediaData(data: MediaData): Unit = setVideoSourceMutex.withLock {
        if (playbackState.value == PlaybackState.DESTROYED) {
            throw IllegalStateException("Instance of MediampPlayer($this) is closed, please create a new instance.")
        }

        // playback has set media data, stop previous first.
        if (playbackState.value >= PlaybackState.READY) {
            val previousResource = openResource.value
            if (data == previousResource?.mediaData) {
                return
            }
            // stop playback if running
            if (playbackState.value >= PlaybackState.PAUSED) {
                stopPlaybackImpl()
            }
            
            openResource.value = null
            previousResource?.releaseResource?.invoke()
        }
        
        val opened = try {
            setMediaDataImpl(data)
        } catch (e: CancellationException) {
            playbackState.value = PlaybackState.ERROR
            throw e
        } catch (e: Exception) {
            playbackState.value = PlaybackState.ERROR
            throw e
        }

        this.openResource.value = opened
        playbackState.value = PlaybackState.READY
    }

    /**
     * Resolves [data] for playback.
     * 
     * @see setMediaData
     */
    protected abstract suspend fun setMediaDataImpl(data: MediaData): D

    final override fun resume() {
        val currState = playbackState.value
        if (currState == PlaybackState.READY || currState == PlaybackState.PAUSED) {
            try {
                resumeImpl()
                playbackState.value = PlaybackState.PLAYING
            } catch (e: Exception) {
                playbackState.value = PlaybackState.ERROR
                throw e
            }
        }
    }

    /**
     * @see resume
     */
    protected abstract fun resumeImpl()
    
    final override fun pause() {
        if (playbackState.value > PlaybackState.PAUSED) {
            try {
                pauseImpl()
                playbackState.value = PlaybackState.PAUSED
            } catch (e: Exception) {
                playbackState.value = PlaybackState.ERROR
                throw e
            }
        }
    }

    /**
     * @see pause
     */
    protected abstract fun pauseImpl()

    final override fun stopPlayback() {
        if (playbackState.value <= PlaybackState.FINISHED) return
        
        try {
            stopPlaybackImpl()
            releaseOpenedMediaData()
            playbackState.value = PlaybackState.FINISHED
        } catch (e: Exception) {
            playbackState.value = PlaybackState.ERROR
            throw e
        }
    }

    /**
     * @see stopPlayback
     */
    protected abstract fun stopPlaybackImpl()

    private fun releaseOpenedMediaData() {
        // TODO: 2024/12/16 proper synchronization?
        val value = openResource.value
        openResource.value = null
        value?.releaseResource?.invoke()
    }

    public final override fun close() {
        if (playbackState.value <= PlaybackState.DESTROYED) return // already closed
        
        try {
            backgroundScope.cancel()
            releaseOpenedMediaData()
            closeImpl()
            playbackState.value = PlaybackState.DESTROYED
        } catch (_: Exception) { }
    }

    /**
     * @see close
     */
    protected abstract fun closeImpl()
}