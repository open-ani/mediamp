/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import org.openani.mediamp.metadata.MediaProperties

/**
 * A level-triggered observation of the backend's native transport, reported by backend
 * adapters via [PlaybackSessionHandle.reportTransport].
 *
 * Adapters report a fresh snapshot on every native change AND synchronously after every
 * `playImpl`/`pauseImpl` return (read-after-command) — so every command yields at least one
 * observation even when it was a native no-op.
 */
public class TransportSnapshot(
    /** The backend's current native play/pause intent (level, not edge). */
    public val nativePlayWhenReady: Boolean,
    /** Whether playback is stalled for data at the current position. */
    public val isStalled: Boolean,
    /**
     * Whether the platform refused to start playback (e.g. browser autoplay policy).
     * When `true`, the machine adopts the observed intent immediately without retrying.
     */
    public val refused: Boolean = false,
) {
    override fun toString(): String =
        "TransportSnapshot(nativePlayWhenReady=$nativePlayWhenReady, isStalled=$isStalled, refused=$refused)"
}

/**
 * The result of a successful [AbstractMediampPlayer.openImpl]: the open handoff (spec §5).
 */
public class OpenResult(
    /**
     * Extra per-session resources to be closed by the machine together with the
     * [org.openani.mediamp.source.MediaData], or `null` if none.
     */
    public val sessionResources: AutoCloseable? = null,
    /**
     * The initial transport observation. `openImpl` MUST have applied the pending intent
     * natively before completing, so a mismatch here takes the *applying* reconciliation
     * path (bounded re-command), never an immediate spurious external-change adoption.
     */
    public val initialSnapshot: TransportSnapshot,
    /**
     * Whether the playhead is already at the end of the media (zero-length media, or
     * `startPositionMillis` at/beyond the end). The machine transitions Ready → Ended in the
     * same turn when `true`.
     */
    public val atEnd: Boolean = false,
    /**
     * Media properties if already known; otherwise deliver later via
     * [PlaybackSessionHandle.notifyProperties].
     */
    public val initialProperties: MediaProperties? = null,
)

/**
 * The handle a backend adapter uses to report facts to the state machine. Issued by the
 * machine to [AbstractMediampPlayer.openImpl]; one handle per media session.
 *
 * All methods are safe to call from any thread; the machine re-dispatches to its main
 * dispatcher and processes facts in order. Facts reported on an invalidated handle (after
 * unload/stop/close/error-entry) are dropped — adapters need no session bookkeeping of
 * their own.
 */
@InternalMediampApi
public interface PlaybackSessionHandle {
    /** Whether this session is still the active one. Racy by nature; informational only. */
    public val isValid: Boolean

    /**
     * The latest seek generation issued by the machine for this session. Adapters stamping a
     * native seek-completion signal read this at signal-processing time (latest-generation
     * attribution: completing generation G closes every generation ≤ G).
     */
    public val currentSeekGeneration: Int

    /** Report the current native transport level (see [TransportSnapshot]). */
    public fun reportTransport(snapshot: TransportSnapshot)

    /**
     * Report that the playhead reached the end of the media. Do NOT report the platform's
     * end-of-media auto-pause as a transport change — it is part of this fact.
     */
    public fun notifyEnded()

    /** Report a fatal error. */
    public fun notifyError(error: PlaybackException)

    /**
     * Report native completion of a seek. Every issued `seekImpl` MUST eventually yield
     * exactly one completion — real, or synthesized at the actual position when the native
     * side refuses/aborts the seek (unseekable/live media must never wedge the machine).
     *
     * @param seekGeneration read from [currentSeekGeneration] at native-signal processing time.
     * @param snapshot a fresh transport observation; the machine runs full reconciliation on it.
     */
    public fun notifySeekCompleted(seekGeneration: Int, positionMillis: Long, snapshot: TransportSnapshot)

    /** Report media properties (metadata) as they become known or change. */
    public fun notifyProperties(properties: MediaProperties)

    /** Report playback position progression. Dropped while a seek is in flight. */
    public fun notifyPosition(positionMillis: Long)
}
