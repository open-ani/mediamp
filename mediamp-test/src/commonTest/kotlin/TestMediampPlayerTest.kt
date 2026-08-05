/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package org.openani.mediamp.test

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.PlayerState
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [TestMediampPlayer]'s own behaviors: the default fake media, the scripting
 * surface (open behaviors, fact injection), and the fake native transport.
 *
 * The state-machine command x status contract is covered by [AbstractMediampPlayerContractTest].
 */
class TestMediampPlayerTest {

    private fun TestScope.createPlayer(): TestMediampPlayer =
        TestMediampPlayer(StandardTestDispatcher(testScheduler))

    private fun newData(uri: String = "file:///fake_video.mp4") =
        UriMediaData(uri, emptyMap(), MediaExtraFiles.EMPTY)

    @Test
    fun `initial state is Idle with no media and position 0`(): TestResult = runTest {
        val player = createPlayer()
        assertEquals(PlayerState.Initial, player.state.value)
        assertNull(player.mediaData.value)
        assertNull(player.mediaProperties.value)
        assertEquals(0L, player.currentPositionMillis.value)
        player.close()
    }

    @Test
    fun `setMediaData reaches Ready paused with default test media`(): TestResult = runTest {
        val player = createPlayer()
        val data = newData()

        player.setMediaData(data)

        assertEquals(PlayerState(MediaStatus.Ready, playWhenReady = false, isBuffering = false), player.state.value)
        assertSame(data, player.mediaData.value)
        val properties = assertNotNull(player.mediaProperties.value)
        assertEquals("Test Video", properties.title)
        assertEquals(100_000L, properties.durationMillis)
        assertEquals(0L, player.currentPositionMillis.value) // starts at 0, not 10_000 (v1 defect T2)
        player.close()
    }

    @Test
    fun `setMediaData with playWhenReady starts playing`(): TestResult = runTest {
        val player = createPlayer()

        player.setMediaData(newData(), playWhenReady = true)

        assertTrue(player.state.value.playWhenReady)
        assertTrue(player.state.value.isPlaying)
        assertTrue(player.nativePlayWhenReady) // intent applied natively during the open handoff
        player.close()
    }

    @Test
    fun `setMediaData applies start position natively`(): TestResult = runTest {
        val player = createPlayer()

        player.setMediaData(newData(), startPositionMillis = 5_000L)

        assertEquals(5_000L, player.currentPositionMillis.value)
        assertEquals(5_000L, player.nativePositionMillis)
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
        player.close()
    }

    @Test
    fun `setMediaData with start position at the end enters Ended in the same turn`(): TestResult = runTest {
        val player = createPlayer()

        player.setMediaData(newData(), startPositionMillis = 100_000L)

        assertEquals(MediaStatus.Ended, player.state.value.mediaStatus)
        assertFalse(player.state.value.playWhenReady)
        assertNotNull(player.mediaData.value) // Ended retains the media
        player.close()
    }

    @Test
    fun `play and pause flip intent synchronously`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(newData())

        player.play()
        assertTrue(player.state.value.playWhenReady) // synchronous, before any native round-trip
        assertTrue(player.state.value.isPlaying)
        advanceUntilIdle()
        assertTrue(player.nativePlayWhenReady) // playImpl ran and reported back

        player.pause()
        assertFalse(player.state.value.playWhenReady)
        advanceUntilIdle()
        assertFalse(player.nativePlayWhenReady)
        player.close()
    }

    @Test
    fun `open hold keeps Opening until released and honors mid-open intent`(): TestResult = runTest {
        val player = createPlayer()
        val hold = TestMediampPlayer.OpenBehavior.Hold()
        player.openBehavior = hold

        val call = launch { player.setMediaData(newData()) }
        advanceUntilIdle()
        assertEquals(MediaStatus.Opening, player.state.value.mediaStatus)
        assertNull(player.mediaData.value) // not installed until the Ready point

        // Intent changes during Opening apply to the in-flight open (spec section 3).
        player.play()
        assertTrue(player.state.value.playWhenReady)
        player.pause()
        assertFalse(player.state.value.playWhenReady)

        hold.release()
        advanceUntilIdle()
        call.join()
        assertEquals(PlayerState(MediaStatus.Ready, playWhenReady = false, isBuffering = false), player.state.value)
        player.close()
    }

    @Test
    fun `open fail throws the same exception that enters Error`(): TestResult = runTest {
        val player = createPlayer()
        val error = PlaybackException(PlaybackErrorCode.IO, "scripted open failure")
        player.openBehavior = TestMediampPlayer.OpenBehavior.Fail(error)

        val thrown = assertFailsWith<PlaybackException> {
            player.setMediaData(newData())
        }

        assertSame(error, thrown)
        val status = assertIs<MediaStatus.Error>(player.state.value.mediaStatus)
        assertSame(error, status.error)
        assertNull(player.mediaData.value) // resources released on Error entry
        assertFalse(player.state.value.playWhenReady)
        player.close()
    }

    @Test
    fun `held open can be failed`(): TestResult = runTest {
        val player = createPlayer()
        val hold = TestMediampPlayer.OpenBehavior.Hold()
        player.openBehavior = hold
        val error = PlaybackException(PlaybackErrorCode.UNSUPPORTED_FORMAT, "bad container")

        var thrown: Throwable? = null
        val call = launch {
            try {
                player.setMediaData(newData())
            } catch (e: PlaybackException) {
                thrown = e
            }
        }
        advanceUntilIdle()
        assertEquals(MediaStatus.Opening, player.state.value.mediaStatus)

        hold.fail(error)
        advanceUntilIdle()
        call.join()
        assertSame(error, thrown) // the suspended caller observed the failure
        val status = assertIs<MediaStatus.Error>(player.state.value.mediaStatus)
        assertSame(error, status.error)
        player.close()
    }

    @Test
    fun `seekTo updates position optimistically and completes synchronously`(): TestResult = runTest {
        val player = createPlayer()
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.events.collect { events += it } }
        player.setMediaData(newData())

        player.seekTo(20_000L)
        assertEquals(20_000L, player.currentPositionMillis.value) // optimistic emission

        advanceUntilIdle()
        assertEquals(20_000L, player.nativePositionMillis)
        assertEquals(
            listOf(20_000L),
            events.filterIsInstance<PlaybackEvent.SeekCompleted>().map { it.positionMillis },
        )
        assertFalse(player.state.value.playWhenReady) // paused seek stays paused
        player.close()
    }

    @Test
    fun `skip is relative to the current position`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(newData())

        player.seekTo(20_000L)
        player.skip(5_000L)
        assertEquals(25_000L, player.currentPositionMillis.value)
        player.skip(-10_000L)
        assertEquals(15_000L, player.currentPositionMillis.value)
        player.close()
    }

    @Test
    fun `seekTo clamps to the known duration and to 0`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(newData())

        player.seekTo(500_000L)
        assertEquals(100_000L, player.currentPositionMillis.value)
        player.seekTo(-5L)
        assertEquals(0L, player.currentPositionMillis.value)
        player.close()
    }

    @Test
    fun `injectStall drives isBuffering while Ready`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(newData(), playWhenReady = true)

        player.injectStall(true)
        advanceUntilIdle()
        assertTrue(player.state.value.isBuffering)
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus) // I1
        assertTrue(player.state.value.playWhenReady) // buffering does not change intent
        assertFalse(player.state.value.isPlaying) // but the clock is not advancing

        player.injectStall(false)
        advanceUntilIdle()
        assertFalse(player.state.value.isBuffering)
        assertTrue(player.state.value.isPlaying)
        player.close()
    }

    @Test
    fun `injectEnded enters Ended and emits MediaEnded`(): TestResult = runTest {
        val player = createPlayer()
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.events.collect { events += it } }
        val data = newData()
        player.setMediaData(data, playWhenReady = true)

        player.injectEnded()
        advanceUntilIdle()

        assertEquals(MediaStatus.Ended, player.state.value.mediaStatus)
        assertFalse(player.state.value.playWhenReady) // I2
        assertEquals(100_000L, player.currentPositionMillis.value) // position snaps to duration
        assertSame(data, player.mediaData.value) // media retained at Ended
        assertFalse(player.nativePlayWhenReady) // machine reconciled native intent (spec section 6)

        val ended = assertNotNull(events.filterIsInstance<PlaybackEvent.MediaEnded>().singleOrNull())
        assertSame(data, ended.mediaData)
        assertEquals(100_000L, ended.durationMillis)
        assertEquals(100_000L, ended.finalPositionMillis)
        player.close()
    }

    @Test
    fun `injectError enters Error and emits ErrorOccurred`(): TestResult = runTest {
        val player = createPlayer()
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.events.collect { events += it } }
        player.setMediaData(newData(), playWhenReady = true)
        val error = PlaybackException(PlaybackErrorCode.DECODING, "decoder died")

        player.injectError(error)
        advanceUntilIdle()

        val status = assertIs<MediaStatus.Error>(player.state.value.mediaStatus)
        assertSame(error, status.error)
        assertFalse(player.state.value.playWhenReady) // I2
        assertNull(player.mediaData.value) // resources released on Error entry
        assertEquals(
            listOf(error),
            events.filterIsInstance<PlaybackEvent.ErrorOccurred>().map { it.error },
        )
        player.close()
    }

    @Test
    fun `injectExternalPlayWhenReady is adopted and published`(): TestResult = runTest {
        val player = createPlayer()
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.events.collect { events += it } }
        player.setMediaData(newData()) // paused

        player.injectExternalPlayWhenReady(true) // e.g. a media-session play button
        advanceUntilIdle()
        assertTrue(player.state.value.playWhenReady)

        player.injectExternalPlayWhenReady(false) // e.g. an audio interruption
        advanceUntilIdle()
        assertFalse(player.state.value.playWhenReady)

        assertEquals(
            listOf(true, false),
            events.filterIsInstance<PlaybackEvent.ExternalPlayWhenReadyChanged>().map { it.value },
        )
        player.close()
    }

    @Test
    fun `refused play is adopted as external pause`(): TestResult = runTest {
        val player = createPlayer()
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.events.collect { events += it } }
        player.setMediaData(newData())
        player.play()
        advanceUntilIdle()
        assertTrue(player.state.value.playWhenReady)

        // The platform refuses playback (e.g. autoplay policy): the machine adopts the pause
        // without retrying (v1 defect W1: stranded-PLAYING is unrepresentable).
        player.injectExternalPlayWhenReady(false, refused = true)
        advanceUntilIdle()

        assertFalse(player.state.value.playWhenReady)
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
        assertEquals(
            listOf(false),
            events.filterIsInstance<PlaybackEvent.ExternalPlayWhenReadyChanged>().map { it.value },
        )
        player.close()
    }

    @Test
    fun `injectPosition and injectProperties update the flows`(): TestResult = runTest {
        val player = createPlayer()
        player.setMediaData(newData(), playWhenReady = true)

        player.injectPosition(42_000L)
        advanceUntilIdle()
        assertEquals(42_000L, player.currentPositionMillis.value)

        val newProperties = assertNotNull(player.mediaProperties.value).copy(durationMillis = 200_000L)
        player.injectProperties(newProperties)
        advanceUntilIdle()
        assertEquals(200_000L, player.mediaProperties.value?.durationMillis)
        player.close()
    }

    @Test
    fun `held seek completes with latest-generation attribution`(): TestResult = runTest {
        val player = createPlayer()
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.events.collect { events += it } }
        player.setMediaData(newData())
        player.holdSeeks = true

        // Rapid seeks coalesce natively: one completion closes every issued generation.
        player.seekTo(10_000L)
        player.seekTo(20_000L)
        advanceUntilIdle()
        assertTrue(events.filterIsInstance<PlaybackEvent.SeekCompleted>().isEmpty())

        player.completeHeldSeek()
        advanceUntilIdle()
        assertEquals(
            listOf(20_000L),
            events.filterIsInstance<PlaybackEvent.SeekCompleted>().map { it.positionMillis },
        )
        assertEquals(20_000L, player.currentPositionMillis.value)
        player.close()
    }

    @Test
    fun `playback speed is stored while paused and applied on play`(): TestResult = runTest {
        val player = createPlayer()
        val speed = player.features.getOrFail(PlaybackSpeed)
        player.setMediaData(newData())

        speed.set(2f)
        assertEquals(2f, speed.value)
        assertEquals(1f, player.nativePlaybackRate) // not playing: stored only (spec section 6)

        player.play()
        assertEquals(2f, player.nativePlaybackRate) // applied on the transition to playing
        player.close()
    }

    @Test
    fun `media metadata feature provides chapters`(): TestResult = runTest {
        val player = createPlayer()
        val metadata = assertNotNull(player.features[MediaMetadata])
        val chapters = assertNotNull(metadata.chapters)
        assertEquals(listOf("chapter1", "chapter2"), chapters.first().map { it.name })
        player.close()
    }

    @Test
    fun `factory creates a functional player`(): TestResult = runTest {
        val player = TestMediampPlayer.Factory.create(Unit, EmptyCoroutineContext)
        assertEquals(TestMediampPlayer::class, TestMediampPlayer.Factory.forClass)
        assertEquals(PlayerState.Initial, player.state.value)
        player.close()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `deprecated playbackState derives v1 values`(): TestResult = runTest {
        val player = createPlayer()
        assertEquals(org.openani.mediamp.PlaybackState.CREATED, player.playbackState.value)

        player.setMediaData(newData())
        // Never played since open: READY regardless of intent/buffering (pinned v1 semantics).
        assertEquals(org.openani.mediamp.PlaybackState.READY, player.playbackState.value)

        player.play()
        assertEquals(org.openani.mediamp.PlaybackState.PLAYING, player.playbackState.value)
        player.pause()
        assertEquals(org.openani.mediamp.PlaybackState.PAUSED, player.playbackState.value)

        player.injectEnded()
        advanceUntilIdle()
        assertEquals(org.openani.mediamp.PlaybackState.FINISHED, player.playbackState.value)

        // Documented v2 change: stopPlayback maps to CREATED (v1 emitted FINISHED).
        player.stopPlayback()
        assertEquals(org.openani.mediamp.PlaybackState.CREATED, player.playbackState.value)

        player.close()
        assertEquals(org.openani.mediamp.PlaybackState.DESTROYED, player.playbackState.value)
    }
}
