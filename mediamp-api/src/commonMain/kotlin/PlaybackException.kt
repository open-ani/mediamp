/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import kotlinx.coroutines.CancellationException

/**
 * Broad classification of a playback failure, for programmatic reactions
 * (e.g. auto-switching to another source on [IO] but not on [UNSUPPORTED_FORMAT]).
 */
public enum class PlaybackErrorCode {
    /** The container or codec is not supported by the backend. */
    UNSUPPORTED_FORMAT,

    /** Network or file I/O failure (unreachable host, 4xx/5xx, read error). */
    IO,

    /** The media was opened but decoding failed mid-playback. */
    DECODING,

    /** Access denied (DRM, authentication, permission). */
    ACCESS_DENIED,

    /** Anything else, including backend-internal failures. */
    INTERNAL,
}

/**
 * A fatal playback failure.
 *
 * Delivered two ways (spec §7):
 * - Failures while opening are thrown from [MediampPlayer.setMediaData] AND emitted as
 *   [MediaStatus.Error] (the same instance). Passive observers (error UI, auto-source-switch)
 *   should react to [MediampPlayer.state]/[MediampPlayer.events]; the thrown exception is for
 *   caller control flow only.
 * - Asynchronous failures during playback are emitted only.
 */
public class PlaybackException(
    public val code: PlaybackErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Thrown from [MediampPlayer.setMediaData] when the call was cancelled by the player
 * machinery rather than by the caller: superseded by a newer [MediampPlayer.setMediaData],
 * or aborted by [MediampPlayer.stopPlayback]/[MediampPlayer.close].
 *
 * This is a [CancellationException] subclass so structured concurrency treats it as
 * cancellation by default. Handlers that catch it to run recovery logic MUST call
 * `currentCoroutineContext().ensureActive()` first — delivery can race the caller's own
 * cancellation, and recovery must not run in an already-cancelled coroutine.
 */
public class MediaLoadCancellationException(
    message: String,
) : CancellationException(message)
