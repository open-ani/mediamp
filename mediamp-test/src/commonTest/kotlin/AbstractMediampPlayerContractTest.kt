/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.test

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.openani.mediamp.AbstractMediampPlayer
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.buildPlayerFeatures
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.UriMediaData
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AbstractMediampPlayerContractTest {
    @Test
    fun `stop playback ignores finished state`() {
        val player = CountingPlayer()

        player.setReady()
        player.stopPlayback()
        player.stopPlayback()

        assertEquals(1, player.stopCalls)
        assertEquals(PlaybackState.FINISHED, player.playbackState.value)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `new set media data cancels previous request without error`(): TestResult = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = UriMediaData("file:///first.mp4", emptyMap(), MediaExtraFiles.EMPTY)
        val second = UriMediaData("file:///second.mp4", emptyMap(), MediaExtraFiles.EMPTY)
        val player = SuspendingSetMediaPlayer(
            dispatcher,
            gates = mapOf(
                first.uri to CompletableDeferred(),
                second.uri to CompletableDeferred(),
            ),
        )

        val firstCall = async { player.setMediaData(first) }
        advanceUntilIdle()
        assertEquals(first.uri, player.startedRequests.first())

        val secondCall = async { player.setMediaData(second) }
        advanceUntilIdle()

        assertTrue(firstCall.isCancelled)
        assertEquals(PlaybackState.CREATED, player.playbackState.value)

        player.release(second.uri)
        advanceUntilIdle()

        secondCall.await()
        assertEquals(PlaybackState.READY, player.playbackState.value)
        assertEquals(second, player.mediaData.first())
        assertEquals(listOf(first.uri, second.uri), player.startedRequests)
    }
}

@OptIn(InternalMediampApi::class, InternalForInheritanceMediampApi::class)
private class CountingPlayer : AbstractMediampPlayer<AbstractMediampPlayer.Data>() {
    override val impl: Any get() = this
    override val mediaProperties: MutableStateFlow<MediaProperties?> = MutableStateFlow(null)
    override val currentPositionMillis: MutableStateFlow<Long> = MutableStateFlow(0L)
    override val features: PlayerFeatures = buildPlayerFeatures {}

    var stopCalls: Int = 0
        private set

    fun setReady() {
        playbackStateDelegate.value = PlaybackState.READY
        openResource.value = Data(UriMediaData("file:///counting.mp4", emptyMap(), MediaExtraFiles.EMPTY))
    }

    override suspend fun setMediaDataImpl(data: MediaData): Data = Data(data)

    override fun resumeImpl() {
        playbackStateDelegate.value = PlaybackState.PLAYING
    }

    override fun pauseImpl() {
        playbackStateDelegate.value = PlaybackState.PAUSED
    }

    override fun stopPlaybackImpl() {
        stopCalls++
        playbackStateDelegate.value = PlaybackState.FINISHED
    }

    override fun closeImpl() {
        playbackStateDelegate.value = PlaybackState.DESTROYED
    }

    override fun getCurrentMediaProperties(): MediaProperties? = mediaProperties.value

    override fun getCurrentPlaybackState(): PlaybackState = playbackState.value

    override fun getCurrentPositionMillis(): Long = currentPositionMillis.value

    override fun seekTo(positionMillis: Long) {
        currentPositionMillis.value = positionMillis
    }
}

@OptIn(InternalMediampApi::class, InternalForInheritanceMediampApi::class)
private class SuspendingSetMediaPlayer(
    dispatcher: CoroutineContext,
    private val gates: Map<String, CompletableDeferred<Unit>>,
) : AbstractMediampPlayer<AbstractMediampPlayer.Data>(dispatcher) {
    override val impl: Any get() = this
    override val mediaProperties: MutableStateFlow<MediaProperties?> = MutableStateFlow(null)
    override val currentPositionMillis: MutableStateFlow<Long> = MutableStateFlow(0L)
    override val features: PlayerFeatures = buildPlayerFeatures {}

    val startedRequests = mutableListOf<String>()

    override suspend fun setMediaDataImpl(data: MediaData): Data {
        val uriMediaData = data as UriMediaData
        startedRequests += uriMediaData.uri
        gates.getValue(uriMediaData.uri).await()
        return Data(data)
    }

    fun release(id: String) {
        gates.getValue(id).complete(Unit)
    }

    override fun resumeImpl() {
        playbackStateDelegate.value = PlaybackState.PLAYING
    }

    override fun pauseImpl() {
        playbackStateDelegate.value = PlaybackState.PAUSED
    }

    override fun stopPlaybackImpl() {
        playbackStateDelegate.value = PlaybackState.FINISHED
    }

    override fun closeImpl() {
        playbackStateDelegate.value = PlaybackState.DESTROYED
    }

    override fun getCurrentMediaProperties(): MediaProperties? = mediaProperties.value

    override fun getCurrentPlaybackState(): PlaybackState = playbackState.value

    override fun getCurrentPositionMillis(): Long = currentPositionMillis.value

    override fun seekTo(positionMillis: Long) {
        currentPositionMillis.value = positionMillis
    }
}
