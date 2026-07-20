/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.internal

import android.util.Log

/**
 * JNI contract for `libmediamp_wsola`. Each [create] call returns an opaque handle for one native
 * WSOLA instance; callers keep the handle and pass it back until [release].
 *
 * The library is loaded on first use. External methods require [isAvailable] and direct buffers.
 * One PCM frame contains one sample for every channel.
 */
internal object WsolaProcessorNative {
    private const val TAG = "WsolaProcessorNative"

    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("mediamp_wsola")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library mediamp_wsola is not available, WSOLA time-stretch disabled", e)
            false
        }
    }

    /** Interleaved signed 16-bit PCM samples (2 bytes per sample). */
    const val SAMPLE_FORMAT_S16: Int = 0

    /** Interleaved 32-bit float PCM samples (4 bytes per sample). */
    const val SAMPLE_FORMAT_FLOAT: Int = 1

    /**
     * Returns an opaque native handle, or `0` when allocation fails.
     *
     * [sampleFormat] is one of [SAMPLE_FORMAT_S16] or [SAMPLE_FORMAT_FLOAT] and fixes the
     * encoding of every buffer passed to [queueInput] and [drainOutput] for this instance.
     */
    external fun create(sampleRate: Int, channels: Int, sampleFormat: Int): Long

    external fun setSpeed(handle: Long, speed: Float)

    /**
     * Queues [frames] frames of interleaved PCM (in the format given to [create]) starting at
     * [byteOffset] in [buf].
     */
    external fun queueInput(handle: Long, buf: java.nio.ByteBuffer, byteOffset: Int, frames: Int)

    /**
     * Drains up to [maxFrames] frames of processed output (in the format given to [create]) from
     * the beginning of [buf].
     * Returns the number written; `0` means more input is needed or an ended stream is drained.
     */
    external fun drainOutput(handle: Long, buf: java.nio.ByteBuffer, maxFrames: Int): Int

    /** Input accepted by JNI but not yet represented in WSOLA output. */
    external fun getPendingInputFrames(handle: Long): Double

    /** Discards buffered audio but keeps the instance, format, and speed. */
    external fun flush(handle: Long)

    /** Closes input; callers must continue [drainOutput] until it returns `0`. */
    external fun finishInput(handle: Long)

    external fun release(handle: Long)
}
