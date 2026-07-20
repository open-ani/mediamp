/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.internal

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Adapts Media3's streaming [AudioProcessor] contract to one native WSOLA instance. It accepts
 * mono/stereo PCM16 and PCM float; backend selection and Sonic fallback live outside this class.
 */
@OptIn(UnstableApi::class)
internal class WsolaAudioProcessor : AudioProcessor {
    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var bytesPerFrame = 0

    private var speed = 1f

    private var handle = 0L

    private var inputEnded = false
    private var outputDrained = false

    // Media3 consumes this buffer by advancing its position; do not overwrite unread output.
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputPending = false

    // JNI requires direct memory. Media3 normally provides it; retain a fallback buffer just in case.
    private var inputScratch: ByteBuffer? = null

    // Used to map playout time back to media time. Native pending frames are excluded at read time.
    private var totalInputFrames = 0L
    private var totalOutputFrames = 0L

    fun setSpeed(speed: Float) {
        require(speed > 0f && speed.isFinite()) { "speed must be finite and positive" }
        this.speed = speed
        if (handle != 0L) {
            WsolaProcessorNative.setSpeed(handle, speed)
        }
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val sampleFormat = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> WsolaProcessorNative.SAMPLE_FORMAT_S16
            C.ENCODING_PCM_FLOAT -> WsolaProcessorNative.SAMPLE_FORMAT_FLOAT
            else -> throw UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount !in 1..2) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        if (this.inputAudioFormat.sampleRate != inputAudioFormat.sampleRate ||
            this.inputAudioFormat.channelCount != inputAudioFormat.channelCount ||
            this.inputAudioFormat.encoding != inputAudioFormat.encoding
        ) {
            releaseHandle()
            handle = WsolaProcessorNative.create(
                inputAudioFormat.sampleRate,
                inputAudioFormat.channelCount,
                sampleFormat,
            )
            if (handle == 0L) {
                throw UnhandledAudioFormatException("native create() failed", inputAudioFormat)
            }
            WsolaProcessorNative.setSpeed(handle, speed)
        }
        this.inputAudioFormat = inputAudioFormat
        bytesPerFrame = inputAudioFormat.channelCount *
            if (sampleFormat == WsolaProcessorNative.SAMPLE_FORMAT_FLOAT) 4 else 2
        return inputAudioFormat
    }

    override fun isActive(): Boolean {
        return handle != 0L && speed != 1f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inputBytes = inputBuffer.remaining()
        require(inputBytes % bytesPerFrame == 0) {
            "PCM input must contain complete frames: bytes=$inputBytes, bytesPerFrame=$bytesPerFrame"
        }
        val frames = inputBytes / bytesPerFrame
        if (frames == 0) {
            return
        }
        val directBuffer =
            if (inputBuffer.isDirect) {
                inputBuffer
            } else {
                val scratch = inputScratch
                    ?.takeIf { it.capacity() >= inputBuffer.remaining() }
                    ?: ByteBuffer.allocateDirect(inputBuffer.remaining())
                        .order(ByteOrder.nativeOrder())
                        .also { inputScratch = it }
                scratch.clear()
                scratch.put(inputBuffer)
                scratch.flip()
                scratch
            }
        WsolaProcessorNative.queueInput(handle, directBuffer, directBuffer.position(), frames)
        inputBuffer.position(inputBuffer.limit())
        totalInputFrames += frames.toLong()
    }

    override fun queueEndOfStream() {
        if (handle != 0L && !inputEnded) {
            WsolaProcessorNative.finishInput(handle)
        }
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        if (outputPending && outputBuffer.hasRemaining()) {
            return outputBuffer
        }
        ensureOutputCapacity()
        val maxFrames = outputBuffer.capacity() / bytesPerFrame
        val frames = WsolaProcessorNative.drainOutput(handle, outputBuffer, maxFrames)
        totalOutputFrames += frames.toLong()
        outputBuffer.position(0)
        outputBuffer.limit(frames * bytesPerFrame)
        if (frames == 0 && inputEnded) {
            outputDrained = true
        }
        outputPending = true
        return outputBuffer
    }

    override fun isEnded(): Boolean = inputEnded && outputDrained

    override fun flush(streamMetadata: AudioProcessor.StreamMetadata) {
        if (handle != 0L) {
            WsolaProcessorNative.flush(handle)
        }
        inputEnded = false
        outputDrained = false
        outputPending = false
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        totalInputFrames = 0L
        totalOutputFrames = 0L
    }

    override fun reset() {
        flush(AudioProcessor.StreamMetadata.DEFAULT)
        releaseHandle()
        inputAudioFormat = AudioFormat.NOT_SET
        bytesPerFrame = 0
        speed = 1f
        inputScratch = null
    }

    /**
     * Maps playout time to media time using frames that have produced output, excluding input still
     * buffered by JNI or WSOLA.
     */
    fun getMediaDuration(playoutDurationUs: Long): Long {
        if (!isActive) {
            return playoutDurationUs
        }
        return if (totalOutputFrames > 0) {
            val pendingInputFrames = WsolaProcessorNative.getPendingInputFrames(handle)
            val processedInputFrames = (totalInputFrames - pendingInputFrames).coerceAtLeast(0.0)
            (playoutDurationUs * processedInputFrames / totalOutputFrames).toLong()
        } else {
            (playoutDurationUs * speed).toLong()
        }
    }

    private fun ensureOutputCapacity() {
        // Capacity is not latency: drainOutput exposes only the frames actually produced.
        val wanted = maxOf(bytesPerFrame * inputAudioFormat.sampleRate / 2, 4096)
        if (outputBuffer === AudioProcessor.EMPTY_BUFFER || outputBuffer.capacity() < wanted) {
            outputBuffer = ByteBuffer.allocateDirect(wanted).order(ByteOrder.nativeOrder())
        }
    }

    private fun releaseHandle() {
        if (handle != 0L) {
            WsolaProcessorNative.release(handle)
            handle = 0L
        }
    }
}
