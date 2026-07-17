/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.internal

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Chooses one time-stretch backend when Media3 configures a stream. Mono/stereo PCM16 and PCM
 * float prefer native WSOLA; unavailable or unsupported streams fall back to
 * [SonicAudioProcessor]. Audio is sent only to the selected backend.
 */
@OptIn(UnstableApi::class)
internal class FallbackTimeStretchAudioProcessor : AudioProcessor {
    enum class Backend {
        /** Media3 has not configured an input format yet. */
        NOT_CONFIGURED,
        WSOLA,
        SONIC,
    }

    var backend: Backend = Backend.NOT_CONFIGURED
        private set

    private val wsola = WsolaAudioProcessor()
    private val sonic = SonicAudioProcessor()
    private var delegate: AudioProcessor = wsola

    fun setSpeed(speed: Float) {
        wsola.setSpeed(speed)
        sonic.setSpeed(speed)
    }

    fun setPitch(pitch: Float) {
        // TODO: Route non-default pitch requests to Sonic. Native WSOLA deliberately preserves
        // the original pitch (1f) while changing playback speed.
        sonic.setPitch(pitch)
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val useWsola = WsolaProcessorNative.isAvailable && isWsolaSupported(inputAudioFormat)
        return if (useWsola) {
            try {
                backend = Backend.WSOLA
                delegate = wsola
                wsola.configure(inputAudioFormat)
            } catch (e: UnhandledAudioFormatException) {
                configureWithSonic(
                    inputAudioFormat,
                    reason = "native WSOLA rejected the audio format",
                    cause = e,
                )
            }
        } else {
            configureWithSonic(
                inputAudioFormat,
                reason = "nativeLoaded=${WsolaProcessorNative.isAvailable}, " +
                    "supportedFormat=${isWsolaSupported(inputAudioFormat)}",
            )
        }
    }

    private fun isWsolaSupported(format: AudioFormat): Boolean {
        return (format.encoding == C.ENCODING_PCM_16BIT || format.encoding == C.ENCODING_PCM_FLOAT) &&
            format.channelCount in 1..2
    }

    private fun configureWithSonic(
        inputAudioFormat: AudioFormat,
        reason: String,
        cause: Throwable? = null,
    ): AudioFormat {
        if (backend != Backend.SONIC) {
            val message = "Falling back from native WSOLA to Sonic: reason=$reason, format=$inputAudioFormat"
            if (cause == null) {
                Log.w(TAG, message)
            } else {
                Log.w(TAG, message, cause)
            }
        }
        backend = Backend.SONIC
        delegate = sonic
        return sonic.configure(inputAudioFormat)
    }

    override fun isActive(): Boolean = delegate.isActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        delegate.queueInput(inputBuffer)
    }

    override fun queueEndOfStream() {
        delegate.queueEndOfStream()
    }

    override fun getOutput(): ByteBuffer = delegate.output

    override fun isEnded(): Boolean = delegate.isEnded

    override fun flush(streamMetadata: AudioProcessor.StreamMetadata) {
        delegate.flush(streamMetadata)
    }

    override fun reset() {
        wsola.reset()
        sonic.reset()
        delegate = wsola
        backend = Backend.NOT_CONFIGURED
    }

    fun getMediaDuration(playoutDurationUs: Long): Long {
        return when (delegate) {
            wsola -> wsola.getMediaDuration(playoutDurationUs)
            else -> if (sonic.isActive) sonic.getMediaDuration(playoutDurationUs) else playoutDurationUs
        }
    }

    private companion object {
        private const val TAG = "FallbackTimeStretch"
    }
}
