/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.test

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.openani.mediamp.AbstractMediampPlayer
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.MediampPlayerFactory
import org.openani.mediamp.OpenResult
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.PlaybackSessionHandle
import org.openani.mediamp.TransportSnapshot
import org.openani.mediamp.features.AspectRatioMode
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
import org.openani.mediamp.metadata.emptyTrackGroup
import org.openani.mediamp.source.MediaData
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KClass

/**
 * A scriptable, fully in-memory [MediampPlayer] for unit tests and previews.
 *
 * This is the reference implementation of the playback state machine's backend SPI
 * (`docs/playback-state-v2.md` §10): it behaves like a real backend adapter — it maintains a
 * fake native transport (play/pause level, stall flag, position) and reports **facts** to the
 * state machine through the [PlaybackSessionHandle], never mutating player state directly.
 * On top of that it exposes a scripting surface for driving arbitrary backend behavior from
 * tests:
 *
 * - [openBehavior] controls how [MediampPlayer.setMediaData] completes: immediately (default),
 *   held until released ([OpenBehavior.Hold]), or failing with a given [PlaybackException]
 *   ([OpenBehavior.Fail]).
 * - [injectStall], [injectEnded], [injectError], [injectExternalPlayWhenReady],
 *   [injectPosition] and [injectProperties] simulate native playback facts exactly the way a
 *   real adapter would report them.
 * - [holdSeeks] and [completeHeldSeek] control native seek completion, for testing the
 *   machine's seek gating. By default seeks complete synchronously.
 *
 * The default fake media has title `"Test Video"` and a duration of 100 seconds
 * ([defaultMediaProperties]); playback position starts at `0` (or at the requested
 * `startPositionMillis`). The fake clock never advances on its own — drive it with
 * [injectPosition].
 *
 * ## Dispatcher
 *
 * The player runs its state machine on the [CoroutineDispatcher] found in [coroutineContext]
 * (its [ContinuationInterceptor]), falling back to [Dispatchers.Unconfined] which processes
 * everything inline — commands and injections take effect synchronously.
 *
 * For virtual-time tests, pass a `StandardTestDispatcher` sharing `runTest`'s scheduler and
 * pump it after injections:
 *
 * ```kotlin
 * @Test
 * fun myTest() = runTest {
 *     val player = TestMediampPlayer(StandardTestDispatcher(testScheduler))
 *     player.setMediaData(UriMediaData("file:///fake.mp4"), playWhenReady = true)
 *     player.injectStall(true)
 *     advanceUntilIdle() // let the state machine process the reported fact
 *     assertTrue(player.state.value.isBuffering)
 * }
 * ```
 *
 * Facts reported by the injection methods are processed by the state machine on its main
 * dispatcher: with a `StandardTestDispatcher` they take effect after `advanceUntilIdle()`
 * (or `runCurrent()`); with the default [Dispatchers.Unconfined] they take effect before the
 * injection call returns.
 */
@OptIn(InternalForInheritanceMediampApi::class, InternalMediampApi::class, ExperimentalMediampApi::class)
public class TestMediampPlayer private constructor(
    coroutineContext: CoroutineContext,
    mainDispatcher: CoroutineDispatcher,
) : AbstractMediampPlayer(
    parentCoroutineContext = coroutineContext,
    mainDispatcher = mainDispatcher,
    isOnMainThread = { true }, // Tests are single-threaded; disable the fail-fast check.
    releaseDispatcher = mainDispatcher, // Deterministic resource release under test schedulers.
) {
    /**
     * @param coroutineContext parent context of the player. The state machine runs on the
     *   [CoroutineDispatcher] found in it, or [Dispatchers.Unconfined] if it contains none;
     *   a [kotlinx.coroutines.Job] in it bounds the player's lifetime. Pass a
     *   `StandardTestDispatcher(testScheduler)` for virtual-time tests (see class KDoc).
     */
    public constructor(coroutineContext: CoroutineContext = EmptyCoroutineContext) : this(
        coroutineContext,
        coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Unconfined,
    )

    override val impl: Any get() = this

    // region scripting surface

    /**
     * How [openImpl] completes the next (and subsequent) [MediampPlayer.setMediaData] calls.
     * @see OpenBehavior
     */
    public var openBehavior: OpenBehavior = OpenBehavior.Immediate

    /**
     * The [MediaProperties] delivered at the Ready point of each open (via
     * [OpenResult.initialProperties]). Change before calling [MediampPlayer.setMediaData]
     * to simulate different media; use [injectProperties] to change them mid-session.
     */
    public var defaultMediaProperties: MediaProperties = MediaProperties(
        title = "Test Video",
        durationMillis = 100_000,
    )

    /**
     * When `true`, native seeks do not complete on their own: [seekImpl] records the target
     * and returns, leaving the machine seek-in-flight (position/ended facts gated per spec §5)
     * until [completeHeldSeek] is called. Default `false`: seeks complete synchronously.
     */
    public var holdSeeks: Boolean = false

    /**
     * Number of times [openImpl] has been invoked. Replay from [org.openani.mediamp.MediaStatus.Ended]
     * and seek-from-Ended must NOT re-open (spec §5) — assert this stays constant.
     */
    public var openCallCount: Int = 0
        private set

    /**
     * Number of times the machine invoked [playImpl]. Intent commands are bounded (spec §5:
     * an attempt budget of 2 re-commands per intent change) — assert this to pin the absence
     * of retry storms.
     */
    public var playCallCount: Int = 0
        private set

    /**
     * Number of times the machine invoked [pauseImpl]. Note the machine issues its own
     * `pauseImpl` on Ended/Error entry when the native side was last reported playing
     * (spec §6) — account for it when asserting.
     */
    public var pauseCallCount: Int = 0
        private set

    /**
     * When `true`, [playImpl]/[pauseImpl] leave [nativePlayWhenReady] unchanged while still
     * emitting their read-after-command report — simulating a platform that silently ignores
     * intent commands without reporting a refusal. Drives the machine's bounded
     * retry-then-adopt path (spec §5: budget exhaustion adopts the observed level).
     */
    public var ignoreIntentCommands: Boolean = false

    /**
     * The playback rate last applied natively via [setRateImpl]. The machine only applies
     * rates while playing (spec §6), so this lags [PlaybackSpeed.value] until playback starts.
     */
    public var nativePlaybackRate: Float = 1f
        private set

    /**
     * Completion behavior of an [openImpl] call, controlled by [openBehavior].
     */
    public sealed interface OpenBehavior {
        /** The open completes immediately (default). */
        public data object Immediate : OpenBehavior

        /**
         * The open suspends (holding the machine in [org.openani.mediamp.MediaStatus.Opening])
         * until [release] or [fail] is called. Each instance is single-use.
         */
        public class Hold : OpenBehavior {
            internal val gate: CompletableDeferred<Unit> = CompletableDeferred()

            /** Lets the held open complete normally (Ready point reached). */
            public fun release() {
                gate.complete(Unit)
            }

            /** Fails the held open with [error], as if the backend rejected the source. */
            public fun fail(error: PlaybackException) {
                gate.completeExceptionally(error)
            }
        }

        /** The open fails immediately with [error]. */
        public class Fail(public val error: PlaybackException) : OpenBehavior
    }

    /**
     * Sets the fake native stall flag (data starvation at the current position) and reports
     * a fresh transport snapshot, like a real adapter observing a native buffering change.
     * Drives [org.openani.mediamp.PlayerState.isBuffering] while the media is Ready.
     */
    public fun injectStall(stalled: Boolean) {
        nativeStalled = stalled
        sessionHandle?.reportTransport(currentTransportSnapshot())
    }

    /**
     * Reports that the fake playhead reached the end of the media, entering
     * [org.openani.mediamp.MediaStatus.Ended] (unless a seek is in flight, per spec §5 gating).
     * The platform's end-of-media auto-pause is NOT reported as a transport change — the
     * machine issues its own `pauseImpl` on Ended entry (spec §6).
     */
    public fun injectEnded() {
        sessionHandle?.notifyEnded()
    }

    /**
     * Reports a fatal native playback error, entering [org.openani.mediamp.MediaStatus.Error]
     * and releasing the loaded [MediaData]. For failing an *open*, use [OpenBehavior.Fail].
     */
    public fun injectError(error: PlaybackException) {
        sessionHandle?.notifyError(error)
    }

    /**
     * Simulates the platform changing the play/pause intent outside of
     * [MediampPlayer.play]/[MediampPlayer.pause] — a media-session button, an audio
     * interruption, or (with [refused] = `true`) the platform refusing a play command
     * (e.g. a browser autoplay policy). Sets the fake native transport level to [value] and
     * reports it; the machine adopts the change and publishes
     * [org.openani.mediamp.PlaybackEvent.ExternalPlayWhenReadyChanged].
     */
    public fun injectExternalPlayWhenReady(value: Boolean, refused: Boolean = false) {
        nativePlayWhenReady = value
        sessionHandle?.reportTransport(
            TransportSnapshot(nativePlayWhenReady = value, isStalled = nativeStalled, refused = refused),
        )
    }

    /**
     * Advances the fake native playback clock to [positionMillis] and reports it, driving
     * [MediampPlayer.currentPositionMillis]. Dropped by the machine while a seek is in flight.
     */
    public fun injectPosition(positionMillis: Long) {
        nativePositionMillis = positionMillis
        sessionHandle?.notifyPosition(positionMillis)
    }

    /**
     * Reports updated [MediaProperties] for the current media (e.g. a late-arriving duration),
     * driving [MediampPlayer.mediaProperties].
     */
    public fun injectProperties(properties: MediaProperties) {
        sessionHandle?.notifyProperties(properties)
    }

    /**
     * Completes the pending native seek at its target position, if [holdSeeks] held one.
     *
     * Attribution follows real engines under coalescing (spec §5): the completion is stamped
     * with the **latest** seek generation at processing time, closing every generation issued
     * so far — rapid seeks yield a single completion, like mpv's `playback-restart`.
     */
    public fun completeHeldSeek() {
        val session = sessionHandle ?: return
        val position = heldSeekPositionMillis ?: return
        heldSeekPositionMillis = null
        nativePositionMillis = position
        session.notifySeekCompleted(session.currentSeekGeneration, position, currentTransportSnapshot())
    }
    // endregion

    // region fake native transport
    private var sessionHandle: PlaybackSessionHandle? = null

    /**
     * The fake native transport's play/pause level, as a real platform player would hold it.
     * Written by [playImpl]/[pauseImpl] (machine-issued commands), [openImpl] (the open
     * handoff) and [injectExternalPlayWhenReady]. Useful for asserting machine-issued native
     * commands, e.g. the `pauseImpl` on Ended entry (spec §6).
     */
    public var nativePlayWhenReady: Boolean = false
        private set

    /**
     * The fake native playback position, updated by [openImpl], completed seeks and
     * [injectPosition]. Useful for asserting that seeks were applied natively.
     */
    public var nativePositionMillis: Long = 0L
        private set

    private var nativeStalled: Boolean = false
    private var heldSeekPositionMillis: Long? = null

    private fun currentTransportSnapshot(): TransportSnapshot = TransportSnapshot(
        nativePlayWhenReady = nativePlayWhenReady,
        isStalled = nativeStalled,
    )
    // endregion

    // region SPI

    override suspend fun openImpl(
        data: MediaData,
        session: PlaybackSessionHandle,
        playWhenReady: Boolean,
        startPositionMillis: Long,
    ): OpenResult {
        openCallCount++
        // The handle is taken at entry, before the open completes: a real backend registers
        // its native listeners while preparing, so facts (e.g. a fatal error) can be reported
        // during Opening. The machine drops facts of sessions that never install (spec §5).
        sessionHandle = session
        when (val behavior = openBehavior) {
            is OpenBehavior.Immediate -> {}
            is OpenBehavior.Hold -> behavior.gate.await() // throws PlaybackException on fail()
            is OpenBehavior.Fail -> throw behavior.error
        }

        val properties = defaultMediaProperties
        val duration = properties.durationMillis
        val clampedStart = if (duration != null) {
            startPositionMillis.coerceIn(0L, duration)
        } else {
            startPositionMillis.coerceAtLeast(0L)
        }

        // Apply the requested intent and start position "natively" before completing the
        // Ready point (spec §3/§5 open handoff).
        nativePositionMillis = clampedStart
        nativePlayWhenReady = playWhenReady
        nativeStalled = false
        heldSeekPositionMillis = null

        return OpenResult(
            sessionResources = null,
            initialSnapshot = currentTransportSnapshot(),
            atEnd = duration != null && clampedStart >= duration,
            initialProperties = properties,
        )
    }

    override fun playImpl() {
        playCallCount++
        if (!ignoreIntentCommands) {
            nativePlayWhenReady = true
        }
        // Read-after-command (spec §5): every intent command yields an observation, even
        // when it was a native no-op.
        sessionHandle?.reportTransport(currentTransportSnapshot())
    }

    override fun pauseImpl() {
        pauseCallCount++
        if (!ignoreIntentCommands) {
            nativePlayWhenReady = false
        }
        sessionHandle?.reportTransport(currentTransportSnapshot())
    }

    override fun seekImpl(positionMillis: Long, seekGeneration: Int) {
        val session = sessionHandle ?: return
        if (holdSeeks) {
            heldSeekPositionMillis = positionMillis
            return
        }
        nativePositionMillis = positionMillis
        session.notifySeekCompleted(seekGeneration, positionMillis, currentTransportSnapshot())
    }

    override fun setRateImpl(rate: Float) {
        nativePlaybackRate = rate
    }

    override fun stopImpl() {
        resetNativeTransport()
    }

    override fun closeImpl() {
        resetNativeTransport()
    }

    private fun resetNativeTransport() {
        sessionHandle = null
        nativePlayWhenReady = false
        nativeStalled = false
        nativePositionMillis = 0L
        heldSeekPositionMillis = null
    }
    // endregion

    override val features: PlayerFeatures = buildPlayerFeatures {
        add(PlaybackSpeed, machinePlaybackSpeed())
        add(
            MediaMetadata,
            object : MediaMetadata {
                override val audioTracks: TrackGroup<AudioTrack> = emptyTrackGroup()
                override val subtitleTracks: TrackGroup<SubtitleTrack> = emptyTrackGroup()
                override val chapters: Flow<List<Chapter>> = MutableStateFlow(
                    listOf(
                        Chapter("chapter1", durationMillis = 90_000L, 0L),
                        Chapter("chapter2", durationMillis = 5_000L, 90_000L),
                    ),
                )
            },
        )
        add(
            VideoAspectRatio,
            object : VideoAspectRatio {
                override val mode: MutableStateFlow<AspectRatioMode> = MutableStateFlow(AspectRatioMode.FIT)
                override fun setMode(mode: AspectRatioMode) {
                    this.mode.value = mode
                }
            },
        )
    }

    /**
     * Creates [TestMediampPlayer] instances; `context` is ignored.
     */
    public object Factory : MediampPlayerFactory<TestMediampPlayer> {
        override val forClass: KClass<TestMediampPlayer> = TestMediampPlayer::class

        override fun create(context: Any, parentCoroutineContext: CoroutineContext): TestMediampPlayer {
            return TestMediampPlayer(parentCoroutineContext)
        }
    }
}
