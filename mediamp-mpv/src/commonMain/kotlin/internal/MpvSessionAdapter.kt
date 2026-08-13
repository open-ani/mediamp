/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import kotlinx.coroutines.CompletableDeferred
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.PlaybackSessionHandle
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Per-media-session bookkeeping of the mpv backend, bridging the persistent mpv event
 * listener to the current [PlaybackSessionHandle].
 *
 * This is deliberately thin: all state transitions are owned by the shared machine
 * (`AbstractMediampPlayer`, spec `docs/playback-state-v2.md`). The adapter only keeps the
 * few native-side latches the machine cannot know:
 *
 * - [pendingOpen]: the Ready point of an open is `MPV_EVENT_FILE_LOADED`; an `END_FILE`
 *   arriving before it fails the open.
 * - Seek-completion attribution ([onSeekIssued]/[onSeekEvent]/[onPlaybackRestart]): mpv
 *   fires `MPV_EVENT_PLAYBACK_RESTART` both after seeks and at initial file load, and
 *   coalesces rapid seeks into a single restart. A restart is reported as a completion
 *   only when a machine-issued generation was stamped by a preceding `MPV_EVENT_SEEK`
 *   (mpv's event stream is FIFO: a seek's `SEEK` always precedes its restart, and the
 *   initial-load restart has no machine-attributable `SEEK` before it).
 * - [eofReached]: mpv's keep-open auto-pause at EOF must not be reported as a transport
 *   change — it is part of the Ended fact (spec §5).
 * - `END_FILE` attribution by playlist entry id ([bindEntryId]/[isStaleEndFile]): a queued
 *   `END_FILE` of a previously unloaded file (episode switch) must not fail or end the
 *   session that replaced it.
 *
 * Threading: command-side members are written from the machine's main thread, event-side
 * members from the mpv event thread. Every field is either single-writer `@Volatile` or a
 * CAS-updated atomic; there is no cross-thread read-then-write on a plain field.
 *
 * @param openStartSeekExpected true when the open passes `start=` to `loadfile`: the start
 *   positioning is a native seek that fires one `MPV_EVENT_SEEK` belonging to the open,
 *   never to a machine seek generation.
 * @param staleEntryIdCeiling the highest playlist entry id observed before this session's
 *   `loadfile`. mpv entry ids are monotonically increasing, so an `END_FILE` carrying an id
 *   at or below it is stale even before this session's own id is known.
 */
@OptIn(InternalMediampApi::class, ExperimentalAtomicApi::class)
internal class MpvSessionAdapter(
    val session: PlaybackSessionHandle,
    openStartSeekExpected: Boolean = false,
    private val staleEntryIdCeiling: Long = 0L,
) {
    /**
     * Completed normally at `MPV_EVENT_FILE_LOADED` (the Ready point, spec §3), or
     * exceptionally by an `END_FILE` that arrives before it. Remains set (completed) for the
     * session lifetime so a late `END_FILE(error)` can be told apart from an open failure via
     * [CompletableDeferred.completeExceptionally]'s return value.
     */
    @Volatile
    var pendingOpen: CompletableDeferred<Unit>? = null

    /**
     * Set (event thread) once `MPV_EVENT_FILE_LOADED` for this session was observed. Seek
     * bookkeeping ignores `SEEK`/`PLAYBACK_RESTART` before it: mpv delivers events in FIFO
     * order, so anything earlier belongs to a previously unloaded file.
     */
    @Volatile
    private var fileLoaded = false

    /**
     * Remaining `MPV_EVENT_SEEK` events that belong to the open itself (`loadfile ...
     * start=` seeks natively to the start position). Consumed on the event thread only.
     */
    @Volatile
    private var openSeekBudget = if (openStartSeekExpected) 1 else 0

    /**
     * {issued, completed} machine seek generations packed into one CAS-updated field
     * (issued in the high 32 bits, completed in the low 32). The machine thread advances
     * `issued` ([onSeekIssued]); the event thread ([onPlaybackRestart]) and the
     * synchronous-refusal path ([onSeekRejected]) advance `completed`. Packing keeps every
     * read-modify-write atomic, so the lost-update race of a plain boolean latch cannot
     * occur. Machine generations start at 1; 0 means "none" on both halves.
     */
    private val seekGenerations = AtomicLong(0L)

    /**
     * Generation stamped at the last machine-attributable `MPV_EVENT_SEEK`: the latest
     * issued generation at that event's processing time (mpv coalesces queued seeks into
     * one execution, so completing it closes every generation ≤ it — spec §5).
     * Event-thread confined.
     */
    @Volatile
    private var seekEventGeneration = 0

    /**
     * This session's playlist entry id; 0 while unknown. Written by the machine thread
     * (synchronous `playlist/0/id` read after `loadfile`) and by the event thread (this
     * session's `START_FILE`) — both write the same id for the same session, so plain
     * last-writer-wins volatile stores suffice.
     */
    @Volatile
    private var boundEntryId = 0L

    /** Last observed `eof-reached` level; [onEofReachedChanged] detects the rising edge. */
    @Volatile
    var eofReached: Boolean = false

    /** Last observed `media-title`, merged with the other media properties. */
    @Volatile
    var lastTitle: String? = null

    /** Last observed duration in milliseconds; `null` = unknown (never a negative sentinel). */
    @Volatile
    var lastDurationMillis: Long? = null

    /** Last observed video display dimensions after applying pixel aspect ratio. */
    @Volatile
    var lastVideoWidth: Int? = null

    @Volatile
    var lastVideoHeight: Int? = null

    /**
     * Records the new `eof-reached` level and returns `true` on the rising edge — the one
     * observation that constitutes the Ended fact.
     */
    fun onEofReachedChanged(value: Boolean): Boolean {
        val was = eofReached
        eofReached = value
        return value && !was
    }

    /** Records that `MPV_EVENT_FILE_LOADED` for this session was observed (event thread). */
    fun onFileLoaded() {
        fileLoaded = true
    }

    /**
     * Records a machine-issued seek generation. Called on the machine thread BEFORE the
     * native `seek` command is sent, so an instantly-executed seek still finds it.
     */
    fun onSeekIssued(generation: Int) {
        while (true) {
            val packed = seekGenerations.load()
            if (seekGenerations.compareAndSet(packed, pack(generation, unpackCompleted(packed)))) return
        }
    }

    /**
     * Marks [generation] completed after mpv rejected the `seek` command synchronously
     * (machine thread). Returns `true` when the caller must synthesize the completion
     * (`false`: a completion for this or a newer generation was already reported).
     */
    fun onSeekRejected(generation: Int): Boolean {
        while (true) {
            val packed = seekGenerations.load()
            if (unpackCompleted(packed) >= generation) return false
            if (seekGenerations.compareAndSet(packed, pack(unpackIssued(packed), generation))) return true
        }
    }

    /**
     * Records an `MPV_EVENT_SEEK` (event thread): mpv executed a seek. The open's own
     * start-position seek is consumed by [openSeekBudget]; a machine-attributable execution
     * stamps the latest issued generation for the restart that follows it (`SEEK` always
     * precedes its `PLAYBACK_RESTART` in mpv's FIFO event stream).
     */
    fun onSeekEvent() {
        if (!fileLoaded) return
        if (openSeekBudget > 0) {
            openSeekBudget--
            return
        }
        seekEventGeneration = unpackIssued(seekGenerations.load())
    }

    /**
     * Processes `MPV_EVENT_PLAYBACK_RESTART` (event thread). Returns the machine seek
     * generation this restart completes, or 0 when it completes none:
     * - no machine seek was executed since the last completion (the restart fired at
     *   initial file load, by the open's `start=` positioning, or by a native-internal
     *   restart such as a track switch) — never a seek completion;
     * - a newer generation was issued after the stamping `SEEK`: its own `SEEK`/restart is
     *   still coming (or its synchronous refusal already synthesized completion), and
     *   reporting now would close the newer seek's gate at a stale position.
     */
    fun onPlaybackRestart(): Int {
        if (!fileLoaded) return 0
        val stamped = seekEventGeneration
        if (stamped == 0) return 0
        while (true) {
            val packed = seekGenerations.load()
            if (unpackIssued(packed) != stamped) return 0
            if (unpackCompleted(packed) >= stamped) return 0
            if (seekGenerations.compareAndSet(packed, pack(stamped, stamped))) return stamped
        }
    }

    /** Binds this session's playlist entry id; ignored while [id] is not a real id. */
    fun bindEntryId(id: Long) {
        if (id > 0) boundEntryId = id
    }

    /**
     * Whether an `END_FILE` carrying [entryId] belongs to a previous playlist entry and must
     * be dropped. Unknown ids (`<= 0`, e.g. natives without entry-id support) never classify
     * as stale — the caller keeps the pre-entry-id behavior.
     */
    fun isStaleEndFile(entryId: Long): Boolean {
        if (entryId <= 0) return false
        if (entryId <= staleEntryIdCeiling) return true
        val bound = boundEntryId
        return bound > 0 && entryId != bound
    }
}

private fun pack(issued: Int, completed: Int): Long =
    (issued.toLong() shl 32) or (completed.toLong() and 0xFFFF_FFFFL)

private fun unpackIssued(packed: Long): Int = (packed ushr 32).toInt()

private fun unpackCompleted(packed: Long): Int = packed.toInt()

// mpv_end_file_reason (mpv/client.h)
internal const val MPV_END_FILE_REASON_EOF: Int = 0
internal const val MPV_END_FILE_REASON_ERROR: Int = 4

// mpv_error (mpv/client.h)
private const val MPV_ERROR_LOADING_FAILED: Int = -13
private const val MPV_ERROR_NOTHING_TO_PLAY: Int = -16
private const val MPV_ERROR_UNKNOWN_FORMAT: Int = -17
private const val MPV_ERROR_UNSUPPORTED: Int = -18

/**
 * Maps an `mpv_error` code (delivered with `END_FILE(reason=error)`) to a [PlaybackException].
 */
internal fun mpvErrorToPlaybackException(mpvError: Int): PlaybackException {
    val code = when (mpvError) {
        MPV_ERROR_LOADING_FAILED -> PlaybackErrorCode.IO
        MPV_ERROR_NOTHING_TO_PLAY,
        MPV_ERROR_UNKNOWN_FORMAT,
        MPV_ERROR_UNSUPPORTED,
        -> PlaybackErrorCode.UNSUPPORTED_FORMAT

        else -> PlaybackErrorCode.INTERNAL
    }
    return PlaybackException(code, "mpv playback failed (mpv_error=$mpvError)")
}
