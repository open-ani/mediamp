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

    override val playbackState: MutableStateFlow<PlaybackState> = MutableStateFlow(PlaybackState.PAUSED_BUFFERING)

    /**
     * Currently playing resource that should be closed when the controller is closed.
     * @see setVideoSource
     */
    protected val openResource: MutableStateFlow<D?> = MutableStateFlow(null)

    public open class Data(
        public open val mediaData: MediaData,
        public open val releaseResource: () -> Unit,
    )

    final override val mediaData: Flow<MediaData?> = openResource.map {
        it?.mediaData
    }

    final override val playbackProgress: Flow<Float>
        get() = combine(mediaProperties.filterNotNull(), currentPositionMillis) { properties, duration ->
            if (properties.durationMillis == 0L) {
                return@combine 0f
            }
            (duration / properties.durationMillis).toFloat().coerceIn(0f, 1f)
        }

    private val setVideoSourceMutex = Mutex()
    final override suspend fun setVideoSource(data: MediaData): Unit = setVideoSourceMutex.withLock {
        val previousResource = openResource.value
        if (data == previousResource?.mediaData) {
            return
        }

        openResource.value = null
        previousResource?.releaseResource?.invoke()

        val opened = try {
            setDataImpl(data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw e
        }

        try {
            playbackState.value = PlaybackState.PAUSED_BUFFERING
            startPlayer(opened)
        } catch (e: CancellationException) {
            opened.releaseResource()
            throw e
        } catch (e: Throwable) {
            opened.releaseResource()
            throw e
        }

        this.openResource.value = opened
    }


    private fun closeVideoSource() {
        // TODO: 2024/12/16 proper synchronization?
        val value = openResource.value
        openResource.value = null
        value?.releaseResource?.invoke()
    }

    final override fun stop() {
        stopImpl()
        closeVideoSource()
    }

    protected abstract fun stopImpl()

    /**
     * 开始播放
     */
    protected abstract suspend fun startPlayer(data: D)

    /**
     * 停止播放, 因为要释放资源了
     */
    protected abstract suspend fun cleanupPlayer()

    protected abstract suspend fun setDataImpl(data: MediaData): D

    private val closed = MutableStateFlow(false)
    public fun close() {
        if (closed.getAndUpdate { true }) return // already closed
        closeImpl()
        closeVideoSource()
        backgroundScope.cancel()
    }

    protected abstract fun closeImpl()
}