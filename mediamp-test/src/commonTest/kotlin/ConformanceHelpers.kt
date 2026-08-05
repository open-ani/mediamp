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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlayerState
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.SeekableInputMediaData
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shared harness for the conformance suite (spec `docs/playback-state-v2.md` section 10).
 *
 * Every recorder checks invariants I1/I2 on every observed emission; recorders collect with an
 * unconfined collector sharing the test scheduler, so every single emission is observed inline
 * (full sequences, no conflation).
 */
internal fun assertStateInvariants(state: PlayerState) {
    // I1: isBuffering implies Ready.
    if (state.isBuffering) {
        assertEquals(MediaStatus.Ready, state.mediaStatus, "I1 violated: $state")
    }
    // I2: playWhenReady implies Opening or Ready.
    if (state.playWhenReady) {
        assertTrue(
            state.mediaStatus == MediaStatus.Opening || state.mediaStatus == MediaStatus.Ready,
            "I2 violated: $state",
        )
    }
}

/**
 * A [PlayerState] emission together with the side-flow values observed *at emission time*.
 * Spec section 9: side-flow writes happen before the state emission that implies them, so
 * these snapshots must already be consistent with [state].
 */
internal class StateObservation(
    val state: PlayerState,
    val mediaData: MediaData?,
    val positionMillis: Long,
    val properties: MediaProperties?,
)

internal class StateRecord {
    val observations: MutableList<StateObservation> = mutableListOf()
    val states: List<PlayerState> get() = observations.map { it.state }
    val statuses: List<MediaStatus> get() = states.map { it.mediaStatus }
}

/** Records every state emission with side-flow snapshots, checking I1/I2 on each. */
internal fun TestScope.recordStatesOf(player: MediampPlayer): StateRecord {
    val record = StateRecord()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        player.state.collect {
            assertStateInvariants(it)
            record.observations += StateObservation(
                state = it,
                mediaData = player.mediaData.value,
                positionMillis = player.currentPositionMillis.value,
                properties = player.mediaProperties.value,
            )
        }
    }
    return record
}

internal class EventRecord {
    val events: MutableList<PlaybackEvent> = mutableListOf()
    inline fun <reified T : PlaybackEvent> ofType(): List<T> = events.filterIsInstance<T>()
}

/** Records every [PlaybackEvent]; subscribes before returning (events have no replay). */
internal fun TestScope.recordEventsOf(player: MediampPlayer): EventRecord {
    val record = EventRecord()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        player.events.collect { record.events += it }
    }
    return record
}

/** A [SeekableInputMediaData] that records [close] calls, for resource-lifecycle assertions. */
internal class TrackingMediaData(
    override val uri: String = "test://tracking",
) : SeekableInputMediaData {
    var closeCalls: Int = 0
        private set
    val closed: Boolean get() = closeCalls > 0

    override val extraFiles: MediaExtraFiles get() = MediaExtraFiles.EMPTY
    override val options: List<String> get() = emptyList()
    override fun fileLength(): Long? = null
    override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput =
        throw UnsupportedOperationException("not used by TestMediampPlayer")

    override fun close() {
        closeCalls++
    }
}

internal fun playerState(
    status: MediaStatus,
    playWhenReady: Boolean = false,
    isBuffering: Boolean = false,
): PlayerState = PlayerState(status, playWhenReady, isBuffering)
