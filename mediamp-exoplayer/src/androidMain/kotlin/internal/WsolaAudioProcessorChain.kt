/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.internal

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor

/**
 * Mirrors Media3's default `silence skipping -> Sonic` chain, replacing the Sonic slot with
 * [FallbackTimeStretchAudioProcessor]. Playback parameters and time accounting follow the default
 * chain contract.
 */
@OptIn(UnstableApi::class)
internal class WsolaAudioProcessorChain : AudioProcessorChain {
    private val silenceSkippingAudioProcessor = SilenceSkippingAudioProcessor()
    private val timeStretchProcessor = FallbackTimeStretchAudioProcessor()
    private val audioProcessors: Array<AudioProcessor> =
        arrayOf(silenceSkippingAudioProcessor, timeStretchProcessor)

    val timeStretchBackend: FallbackTimeStretchAudioProcessor.Backend
        get() = timeStretchProcessor.backend

    override fun getAudioProcessors(): Array<AudioProcessor> = audioProcessors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        timeStretchProcessor.setSpeed(playbackParameters.speed)
        timeStretchProcessor.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silenceSkippingAudioProcessor.setEnabled(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDurationUs: Long): Long {
        return if (timeStretchProcessor.isActive) {
            timeStretchProcessor.getMediaDuration(playoutDurationUs)
        } else {
            playoutDurationUs
        }
    }

    override fun getSkippedOutputFrameCount(): Long = silenceSkippingAudioProcessor.skippedFrames
}

/** Installs [WsolaAudioProcessorChain] while keeping Media3's default renderers and audio sink. */
@OptIn(UnstableApi::class)
internal class WsolaRenderersFactory(
    context: Context,
) : DefaultRenderersFactory(context) {
    /** The most recently installed chain, exposed for diagnostics and tests. */
    var audioProcessorChain: WsolaAudioProcessorChain? = null
        private set

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val chain = WsolaAudioProcessorChain().also { audioProcessorChain = it }
        Log.i(TAG, "Installed WSOLA audio processor chain (fallback to Sonic if unavailable)")
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(chain)
            .build()
    }

    private companion object {
        private const val TAG = "WsolaRenderersFactory"
    }
}
