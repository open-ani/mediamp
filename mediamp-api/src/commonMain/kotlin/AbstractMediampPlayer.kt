/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.source.MediaData
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The single-writer playback state machine implementing the v2 state specification
 * (`docs/playback-state-v2.md`). All MediampPlayer backends extend this class.
 *
 * ## Contract for implementors
 *
 * The machine is the sole writer of every public flow. Backends:
 * - implement the `*Impl` command methods, which the machine invokes only on [mainDispatcher]'s
 *   thread;
 * - report native facts through the [PlaybackSessionHandle] issued to [openImpl] — from any
 *   thread; the machine re-orders them onto the main dispatcher and drops stale-session facts.
 *
 * Backends never mutate state directly and never call `*Impl` methods themselves.
 *
 * @param parentCoroutineContext parent for the machine's internal scope; its Job (if any)
 *   bounds the machine's lifetime — when it completes, the player closes itself.
 * @param mainDispatcher the dispatcher the machine is confined to (spec §4). Must dispatch to
 *   a single thread. Defaults to [Dispatchers.Main].
 * @param isOnMainThread platform check used for the fail-fast command thread assertion and
 *   for close() trampolining. Backends pass a platform-accurate implementation (e.g.
 *   `Looper.getMainLooper().isCurrentThread` on Android). Defaulting to `{ true }` disables
 *   the assertion (single-threaded platforms, tests).
 */
@InternalMediampApi
@OptIn(InternalForInheritanceMediampApi::class, ExperimentalMediampApi::class)
public abstract class AbstractMediampPlayer(
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    final override val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val isOnMainThread: () -> Boolean = { true },
    private val releaseDispatcher: CoroutineContext = Dispatchers.Default,
) : MediampPlayer {

    // region public flows (machine is the sole writer)
    private val _state = MutableStateFlow(PlayerState.Initial)
    final override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackEvent>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    final override val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    private val _mediaData = MutableStateFlow<MediaData?>(null)
    final override val mediaData: StateFlow<MediaData?> = _mediaData.asStateFlow()

    private val _mediaProperties = MutableStateFlow<MediaProperties?>(null)
    final override val mediaProperties: StateFlow<MediaProperties?> = _mediaProperties.asStateFlow()

    private val _currentPositionMillis = MutableStateFlow(0L)
    final override val currentPositionMillis: StateFlow<Long> = _currentPositionMillis.asStateFlow()

    final override val playbackProgress: Flow<Float> =
        combine(mediaProperties.filterNotNull(), currentPositionMillis) { properties, position ->
            val duration = properties.durationMillis
            if (duration == null || duration == 0L) 0f
            else (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        }

    @Suppress("DEPRECATION")
    private val _legacyPlaybackState = MutableStateFlow(PlaybackState.CREATED)

    @Deprecated("Use state instead. See docs/playback-state-v2.md for the mapping.")
    @Suppress("DEPRECATION")
    final override val playbackState: StateFlow<PlaybackState> = _legacyPlaybackState.asStateFlow()
    // endregion

    // region machine state (main-thread confined)
    private val mainScope = CoroutineScope(
        parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job]) + mainDispatcher,
    )

    /** Never-cancelled scope for close trampolining and resource release. */
    private val cleanupScope = CoroutineScope(NonCancellable + mainDispatcher)

    private var epochCounter = 0L
    private var activeSession: SessionImpl? = null
    private var activeOpen: OpenAttempt? = null

    /** Latch for the deprecated [playbackState] derivation: true until first isPlaying. */
    private var legacyNeverPlayed = true

    // Intent reconciliation (spec §5)
    private var lastReportedNativePwr: Boolean? = null
    private var applyingIntent = false
    private var intentRetryBudget = 0

    // Seek gating (spec §5)
    private var seekGeneration = 0
    private var seekInFlight = false

    // Playback speed through the machine (spec §6)
    private var desiredRate = 1f

    private val closed = MutableStateFlow(false)

    // Declared before the init block: the drain loop launched there may run immediately when
    // mainDispatcher executes inline (e.g. Dispatchers.Unconfined in tests), so this field
    // must already be initialized at that point.
    private val factChannel = Channel<Fact>(Channel.UNLIMITED)
    // endregion

    init {
        // Fact drain loop: all backend notifications funnel through this channel, giving
        // cross-thread FIFO ordering and drain-after-commit semantics for re-entrant reports.
        mainScope.launch {
            for (fact in factChannel) {
                processFact(coalesce(fact))
            }
        }
        // Machine lifetime bounded by parent: close when the parent completes.
        mainScope.coroutineContext[Job]?.invokeOnCompletion {
            close()
        }
    }

    // region SPI — implemented by backends

    /**
     * Opens [data] and prepares it to the Ready point (spec §3): source accepted and metadata
     * available. Called on the main dispatcher; hop to IO internally for blocking work.
     *
     * Must apply [playWhenReady] and [startPositionMillis] natively before returning, so the
     * returned [OpenResult.initialSnapshot] matches the requested intent unless the platform
     * refused. Must throw [PlaybackException] on open failure. Must observe cancellation.
     */
    protected abstract suspend fun openImpl(
        data: MediaData,
        session: PlaybackSessionHandle,
        playWhenReady: Boolean,
        startPositionMillis: Long,
    ): OpenResult

    /** Start/resume native playback. Report a fresh [TransportSnapshot] after returning. */
    protected abstract fun playImpl()

    /** Pause native playback. Report a fresh [TransportSnapshot] after returning. */
    protected abstract fun pauseImpl()

    /**
     * Seek natively. Every issued seek MUST eventually produce exactly one
     * [PlaybackSessionHandle.notifySeekCompleted] (real or synthesized on refusal).
     */
    protected abstract fun seekImpl(positionMillis: Long, seekGeneration: Int)

    /** Apply playback rate natively. Must not change transport state (spec §6). */
    protected abstract fun setRateImpl(rate: Float)

    /** Stop and unload the current native media. Must not require a session to be active. */
    protected abstract fun stopImpl()

    /**
     * Release the native player. Called once, on the main dispatcher, after Released has been
     * emitted and the session detached; heavy teardown may continue on a background thread.
     */
    protected abstract fun closeImpl()
    // endregion

    // region commands

    final override fun play() {
        checkMainThread("play")
        when (_state.value.mediaStatus) {
            MediaStatus.Opening -> {
                activeOpen?.playWhenReady = true
                commit(_state.value.copy(playWhenReady = true))
            }
            MediaStatus.Ready -> {
                if (!_state.value.playWhenReady) {
                    commit(_state.value.copy(playWhenReady = true))
                    issueIntentCommand(true)
                }
            }
            MediaStatus.Ended -> replay()
            else -> {}
        }
    }

    final override fun pause() {
        checkMainThread("pause")
        when (_state.value.mediaStatus) {
            MediaStatus.Opening -> {
                activeOpen?.playWhenReady = false
                commit(_state.value.copy(playWhenReady = false))
            }
            MediaStatus.Ready -> {
                if (_state.value.playWhenReady) {
                    commit(_state.value.copy(playWhenReady = false))
                    issueIntentCommand(false)
                }
            }
            else -> {}
        }
    }

    final override fun seekTo(positionMillis: Long) {
        checkMainThread("seekTo")
        when (_state.value.mediaStatus) {
            MediaStatus.Opening -> {
                val clamped = clampPosition(positionMillis)
                activeOpen?.startPositionMillis = clamped
                _currentPositionMillis.value = clamped
            }
            MediaStatus.Ready -> issueSeek(positionMillis)
            MediaStatus.Ended -> {
                // Seek from Ended: back to Ready, paused, at the clamped position.
                val clamped = clampPosition(positionMillis)
                _currentPositionMillis.value = clamped
                commit(PlayerState(MediaStatus.Ready, playWhenReady = false, isBuffering = false))
                startSeekWindow()
                seekImpl(clamped, seekGeneration)
            }
            else -> {}
        }
    }

    final override fun stopPlayback() {
        checkMainThread("stopPlayback")
        when (_state.value.mediaStatus) {
            MediaStatus.Opening -> {
                cancelActiveOpen("Aborted by stopPlayback")
                resetSideFlowsToIdle()
                commit(PlayerState.Initial)
            }
            MediaStatus.Ready, MediaStatus.Ended -> {
                unloadActiveSession()
                resetSideFlowsToIdle()
                commit(PlayerState.Initial)
            }
            is MediaStatus.Error -> {
                commit(PlayerState.Initial)
            }
            else -> {}
        }
    }

    final override fun close() {
        if (closed.value) return
        if (isOnMainThread()) {
            doClose()
        } else {
            cleanupScope.launch { doClose() }
        }
    }

    private fun doClose() {
        if (closed.value) return
        closed.value = true
        cancelActiveOpen("Player closed")
        unloadActiveSession()
        resetSideFlowsToIdle()
        commit(PlayerState(MediaStatus.Released, playWhenReady = false, isBuffering = false))
        closeImpl()
        mainScope.coroutineContext[Job]?.cancel()
    }

    final override suspend fun setMediaData(
        data: MediaData,
        playWhenReady: Boolean,
        startPositionMillis: Long,
    ) {
        // The machine takes ownership of `data` at call entry (spec §3): every exit path
        // either installs it or releases it exactly once.
        val attempt = withContext(mainDispatcher) {
            if (closed.value || _state.value.mediaStatus == MediaStatus.Released) {
                releaseAsync(data, null)
                return@withContext null
            }
            val current = activeSession
            if (current != null && current.mediaData === data) {
                // Equal-data at Ready/Ended: no reopen; params still applied.
                applyParamsToCurrentSession(playWhenReady, startPositionMillis)
                return@withContext null
            }
            cancelActiveOpen("Superseded by a newer setMediaData call")
            unloadActiveSession()

            val newAttempt = OpenAttempt(data, playWhenReady, clampToNonNegative(startPositionMillis))
            activeOpen = newAttempt
            // §9: side flows first, then Opening.
            _mediaData.value = null
            _mediaProperties.value = null
            _currentPositionMillis.value = newAttempt.startPositionMillis
            commit(PlayerState(MediaStatus.Opening, playWhenReady = playWhenReady, isBuffering = false))

            newAttempt.job = mainScope.launch { runOpen(newAttempt) }
            newAttempt
        } ?: return

        try {
            attempt.completion.await()
        } catch (e: CancellationException) {
            // Caller cancelled (or machine cancelled us — MediaLoadCancellationException).
            // If the open had not completed, abort it; if Ready already committed, the
            // session stays intact (spec §3).
            if (!attempt.installed) {
                attempt.job?.cancel(MediaLoadCancellationException("Caller cancelled"))
            }
            throw e
        }
    }

    private suspend fun runOpen(attempt: OpenAttempt) {
        val session = SessionImpl(epochCounter++, attempt.data)
        try {
            val result = openImpl(attempt.data, session, attempt.playWhenReady, attempt.startPositionMillis)
            if (activeOpen !== attempt || closed.value) {
                // Superseded/closed after openImpl completed but before install.
                session.invalidate()
                releaseAsync(attempt.data, result.sessionResources)
                attempt.completion.completeExceptionally(
                    MediaLoadCancellationException("Superseded before install"),
                )
                return
            }
            installSession(attempt, session, result)
            attempt.installed = true
            attempt.completion.complete(Unit)
        } catch (e: CancellationException) {
            session.invalidate()
            releaseAsync(attempt.data, null)
            if (activeOpen === attempt) {
                activeOpen = null
                // This attempt owned the active Opening: back to Idle (spec §3).
                if (_state.value.mediaStatus == MediaStatus.Opening) {
                    resetSideFlowsToIdle()
                    commit(PlayerState.Initial)
                }
            }
            attempt.completion.completeExceptionally(
                (e as? MediaLoadCancellationException) ?: MediaLoadCancellationException("Open cancelled"),
            )
        } catch (e: Throwable) {
            session.invalidate()
            releaseAsync(attempt.data, null)
            val error = e as? PlaybackException
                ?: PlaybackException(PlaybackErrorCode.INTERNAL, "openImpl failed: ${e.message}", e)
            if (activeOpen === attempt) {
                activeOpen = null
                errorEntry(error)
            }
            attempt.completion.completeExceptionally(error)
        }
    }

    private fun installSession(attempt: OpenAttempt, session: SessionImpl, result: OpenResult) {
        activeOpen = null
        activeSession = session
        session.resources = result.sessionResources
        legacyNeverPlayed = true
        lastReportedNativePwr = result.initialSnapshot.nativePlayWhenReady
        // Handoff intent mismatch takes the applying path (spec §5) — openImpl already
        // applied the intent, so a mismatch is "command in flight or refused", never external.
        applyingIntent = true
        intentRetryBudget = 2
        seekInFlight = false

        // §9: side flows before the status emission.
        if (result.initialProperties != null) {
            _mediaProperties.value = result.initialProperties
        }
        _mediaData.value = attempt.data
        _currentPositionMillis.value = attempt.startPositionMillis
        commit(PlayerState(MediaStatus.Ready, playWhenReady = attempt.playWhenReady, isBuffering = false))

        // Process the handoff snapshot, then atEnd, in the same turn (spec §5).
        reconcileTransport(result.initialSnapshot)
        if (result.atEnd) {
            endedEntry()
        }
    }
    // endregion

    // region transitions

    /**
     * Commits a snapshot: legacy derivation, state emission, and deferred event delivery.
     * MUST be the last state mutation of any transition; events fire after the state commit.
     */
    private fun commit(snapshot: PlayerState, vararg deferredEvents: PlaybackEvent) {
        if (snapshot.isPlaying && legacyNeverPlayed) {
            legacyNeverPlayed = false
        }
        val risingPlay = snapshot.isPlaying && !_state.value.isPlaying
        _state.value = snapshot
        _legacyPlaybackState.value = deriveLegacy(snapshot)
        if (risingPlay && activeSession != null && desiredRate != 1f) {
            setRateImpl(desiredRate)
        }
        for (event in deferredEvents) {
            _events.tryEmit(event)
        }
    }

    @Suppress("DEPRECATION")
    private fun deriveLegacy(s: PlayerState): PlaybackState = when (s.mediaStatus) {
        MediaStatus.Idle, MediaStatus.Opening -> PlaybackState.CREATED
        MediaStatus.Released -> PlaybackState.DESTROYED
        MediaStatus.Ended -> PlaybackState.FINISHED
        is MediaStatus.Error -> PlaybackState.ERROR
        MediaStatus.Ready -> when {
            legacyNeverPlayed -> PlaybackState.READY
            s.playWhenReady && s.isBuffering -> PlaybackState.PAUSED_BUFFERING
            s.playWhenReady -> PlaybackState.PLAYING
            else -> PlaybackState.PAUSED
        }
    }

    private fun endedEntry() {
        val session = activeSession ?: return
        val duration = _mediaProperties.value?.durationMillis
        if (duration != null) {
            _currentPositionMillis.value = duration
        }
        // Intent reconciliation (spec §6): stop a native side that could still be playing.
        if (lastReportedNativePwr == true) {
            applyingIntent = true
            intentRetryBudget = 2
            pauseImpl()
        }
        commit(
            PlayerState(MediaStatus.Ended, playWhenReady = false, isBuffering = false),
            PlaybackEvent.MediaEnded(session.mediaData, _currentPositionMillis.value, duration),
        )
    }

    private fun errorEntry(error: PlaybackException) {
        unloadActiveSession()
        resetSideFlowsToIdle()
        commit(
            PlayerState(MediaStatus.Error(error), playWhenReady = false, isBuffering = false),
            PlaybackEvent.ErrorOccurred(error),
        )
    }

    private fun replay() {
        val session = activeSession ?: return
        _currentPositionMillis.value = 0L
        commit(PlayerState(MediaStatus.Ready, playWhenReady = true, isBuffering = false))
        startSeekWindow()
        seekImpl(0L, seekGeneration)
        issueIntentCommand(true)
    }

    private fun issueSeek(positionMillis: Long) {
        val clamped = clampPosition(positionMillis)
        _currentPositionMillis.value = clamped
        startSeekWindow()
        seekImpl(clamped, seekGeneration)
    }

    private fun startSeekWindow() {
        seekGeneration++
        seekInFlight = true
    }

    private fun issueIntentCommand(desired: Boolean) {
        applyingIntent = true
        intentRetryBudget = 2
        if (desired) playImpl() else pauseImpl()
    }

    private fun applyParamsToCurrentSession(playWhenReady: Boolean, startPositionMillis: Long) {
        when (_state.value.mediaStatus) {
            MediaStatus.Ready -> {
                if (startPositionMillis != _currentPositionMillis.value) {
                    issueSeek(startPositionMillis)
                }
                if (playWhenReady != _state.value.playWhenReady) {
                    commit(_state.value.copy(playWhenReady = playWhenReady))
                    issueIntentCommand(playWhenReady)
                }
            }
            MediaStatus.Ended -> {
                // Replay semantics: seek to the start position and apply the intent.
                val clamped = clampPosition(startPositionMillis)
                _currentPositionMillis.value = clamped
                commit(PlayerState(MediaStatus.Ready, playWhenReady = playWhenReady, isBuffering = false))
                startSeekWindow()
                seekImpl(clamped, seekGeneration)
                if (playWhenReady) issueIntentCommand(true)
            }
            else -> {}
        }
    }
    // endregion

    // region fact processing

    private sealed class Fact {
        abstract val session: SessionImpl

        class Transport(override val session: SessionImpl, val snapshot: TransportSnapshot) : Fact()
        class Ended(override val session: SessionImpl) : Fact()
        class Error(override val session: SessionImpl, val error: PlaybackException) : Fact()
        class SeekCompleted(
            override val session: SessionImpl,
            val generation: Int,
            val positionMillis: Long,
            val snapshot: TransportSnapshot,
        ) : Fact()

        class Properties(override val session: SessionImpl, val properties: MediaProperties) : Fact()
        class Position(override val session: SessionImpl, val positionMillis: Long) : Fact()
    }

    /**
     * Coalesces queued transport reports to the newest level at drain time (spec §5): stale
     * event-thread captures must not consume the retry budget or masquerade as external.
     */
    private fun coalesce(first: Fact): Fact {
        if (first !is Fact.Transport) return first
        var newest = first
        while (true) {
            val next = factChannel.tryReceive().getOrNull() ?: return newest
            if (next is Fact.Transport && next.session === newest.session) {
                newest = next
            } else {
                // Non-transport fact (or different session): process the coalesced transport
                // now and re-queue the interloper for the next loop turn.
                factChannel.trySend(next)
                return newest
            }
        }
    }

    private fun processFact(fact: Fact) {
        if (!fact.session.isValid || closed.value) return
        when (fact) {
            is Fact.Transport -> {
                when (_state.value.mediaStatus) {
                    MediaStatus.Ready -> reconcileTransport(fact.snapshot)
                    MediaStatus.Ended, is MediaStatus.Error -> {
                        // Bookkeeping only (spec §5): tracking must not go stale.
                        lastReportedNativePwr = fact.snapshot.nativePlayWhenReady
                        applyingIntent = false
                    }
                    MediaStatus.Opening -> {
                        // openImpl owns the native side during open; only a refusal is adopted
                        // into the pending intent (a stale queued snapshot cannot override a
                        // user's mid-open pause).
                        lastReportedNativePwr = fact.snapshot.nativePlayWhenReady
                        if (fact.snapshot.refused && _state.value.playWhenReady) {
                            activeOpen?.playWhenReady = false
                            commit(
                                _state.value.copy(playWhenReady = false),
                                PlaybackEvent.ExternalPlayWhenReadyChanged(false),
                            )
                        }
                    }
                    else -> {}
                }
            }
            is Fact.Ended -> {
                if (seekInFlight) return
                if (_state.value.mediaStatus == MediaStatus.Ready) endedEntry()
            }
            is Fact.Error -> {
                when (_state.value.mediaStatus) {
                    MediaStatus.Ready, MediaStatus.Ended -> errorEntry(fact.error)
                    MediaStatus.Opening -> {
                        // Fail the in-flight open (spec §5 matrix): cancel it so a late
                        // openImpl success cannot commit Ready on top of Error, and deliver
                        // the error to the setMediaData caller.
                        val attempt = activeOpen
                        if (attempt != null) {
                            activeOpen = null
                            attempt.job?.cancel(MediaLoadCancellationException("Open failed: ${fact.error.message}"))
                            errorEntry(fact.error)
                            attempt.completion.completeExceptionally(fact.error)
                        } else {
                            errorEntry(fact.error)
                        }
                    }
                    else -> {}
                }
            }
            is Fact.SeekCompleted -> {
                if (fact.generation < seekGeneration) return // stale; a newer seek is pending
                if (seekInFlight) {
                    seekInFlight = false
                    _currentPositionMillis.value = fact.positionMillis
                    _events.tryEmit(PlaybackEvent.SeekCompleted(fact.positionMillis))
                }
                if (_state.value.mediaStatus == MediaStatus.Ready) {
                    reconcileTransport(fact.snapshot)
                }
            }
            is Fact.Properties -> {
                when (_state.value.mediaStatus) {
                    MediaStatus.Opening, MediaStatus.Ready, MediaStatus.Ended ->
                        _mediaProperties.value = fact.properties
                    else -> {}
                }
            }
            is Fact.Position -> {
                if (seekInFlight) return
                when (_state.value.mediaStatus) {
                    MediaStatus.Ready -> _currentPositionMillis.value = fact.positionMillis
                    else -> {}
                }
            }
        }
    }

    /**
     * Desired-vs-observed intent reconciliation plus data-axis sync (spec §5).
     * Only called while status is Ready.
     */
    private fun reconcileTransport(snapshot: TransportSnapshot) {
        lastReportedNativePwr = snapshot.nativePlayWhenReady
        val current = _state.value
        val observed = snapshot.nativePlayWhenReady
        val desired = current.playWhenReady

        var newPwr = desired
        var externalChange = false

        if (observed == desired) {
            applyingIntent = false
        } else if (snapshot.refused) {
            applyingIntent = false
            newPwr = observed
            externalChange = true
        } else if (applyingIntent && intentRetryBudget > 0) {
            intentRetryBudget--
            if (desired) playImpl() else pauseImpl()
        } else {
            // External change (or retry budget exhausted): adopt.
            applyingIntent = false
            newPwr = observed
            externalChange = true
        }

        val newSnapshot = PlayerState(MediaStatus.Ready, newPwr, snapshot.isStalled)
        if (newSnapshot != current || externalChange) {
            if (externalChange) {
                commit(newSnapshot, PlaybackEvent.ExternalPlayWhenReadyChanged(newPwr))
                if (newPwr && desiredRate != 1f) {
                    // Re-apply the stored rate after an accepted external play (spec §6).
                    setRateImpl(desiredRate)
                }
            } else {
                commit(newSnapshot)
            }
        }
    }
    // endregion

    // region sessions & resources

    private inner class SessionImpl(
        val epoch: Long,
        val mediaData: MediaData,
    ) : PlaybackSessionHandle {
        @Volatile
        private var valid = true
        var resources: AutoCloseable? = null

        override val isValid: Boolean get() = valid && !closed.value
        override val currentSeekGeneration: Int get() = seekGeneration

        fun invalidate() {
            valid = false
        }

        override fun reportTransport(snapshot: TransportSnapshot) {
            if (valid) factChannel.trySend(Fact.Transport(this, snapshot))
        }

        override fun notifyEnded() {
            if (valid) factChannel.trySend(Fact.Ended(this))
        }

        override fun notifyError(error: PlaybackException) {
            if (valid) factChannel.trySend(Fact.Error(this, error))
        }

        override fun notifySeekCompleted(seekGeneration: Int, positionMillis: Long, snapshot: TransportSnapshot) {
            if (valid) factChannel.trySend(Fact.SeekCompleted(this, seekGeneration, positionMillis, snapshot))
        }

        override fun notifyProperties(properties: MediaProperties) {
            if (valid) factChannel.trySend(Fact.Properties(this, properties))
        }

        override fun notifyPosition(positionMillis: Long) {
            if (valid) factChannel.trySend(Fact.Position(this, positionMillis))
        }
    }

    private class OpenAttempt(
        val data: MediaData,
        @Volatile var playWhenReady: Boolean,
        @Volatile var startPositionMillis: Long,
    ) {
        var job: Job? = null
        val completion: CompletableDeferred<Unit> = CompletableDeferred()

        @Volatile
        var installed: Boolean = false
    }

    private fun unloadActiveSession() {
        val session = activeSession ?: return
        activeSession = null
        session.invalidate()
        stopImpl()
        releaseAsync(session.mediaData, session.resources)
        seekInFlight = false
        applyingIntent = false
        lastReportedNativePwr = null
    }

    private fun cancelActiveOpen(reason: String) {
        val attempt = activeOpen ?: return
        activeOpen = null
        attempt.job?.cancel(MediaLoadCancellationException(reason))
    }

    private fun releaseAsync(data: MediaData, resources: AutoCloseable?) {
        cleanupScope.launch(releaseDispatcher) {
            // MediaData.close is idempotent per its contract; exactly-once here by ownership.
            runCatching { data.close() }
            runCatching { resources?.close() }
        }
    }

    private fun resetSideFlowsToIdle() {
        _mediaData.value = null
        _mediaProperties.value = null
        _currentPositionMillis.value = 0L
    }
    // endregion

    // region helpers

    private fun clampPosition(positionMillis: Long): Long {
        val duration = _mediaProperties.value?.durationMillis
        val lower = positionMillis.coerceAtLeast(0L)
        return if (duration != null && duration >= 0) lower.coerceAtMost(duration) else lower
    }

    private fun clampToNonNegative(value: Long): Long = value.coerceAtLeast(0L)

    private fun checkMainThread(method: String) {
        check(isOnMainThread()) {
            "MediampPlayer.$method must be called on the player's main thread (mainDispatcher=$mainDispatcher)"
        }
    }

    /**
     * Creates a [PlaybackSpeed] feature backed by the machine (spec §6): while not playing the
     * rate is only stored; the machine applies it on transitions to playing and after an
     * accepted external play. Backends should register this in their features.
     */
    protected fun machinePlaybackSpeed(): PlaybackSpeed = object : PlaybackSpeed {
        private val flow = MutableStateFlow(1f)
        override val valueFlow: Flow<Float> get() = flow
        override val value: Float get() = flow.value

        override fun set(speed: Float) {
            val coerced = speed.coerceAtLeast(0.1f)
            flow.value = coerced
            desiredRate = coerced
            if (_state.value.isPlaying) {
                setRateImpl(coerced)
            }
        }
    }
    // endregion
}
