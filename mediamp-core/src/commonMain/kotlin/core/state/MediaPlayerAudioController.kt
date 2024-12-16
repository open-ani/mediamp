package org.openani.mediamp.core.state

import androidx.annotation.UiThread
import kotlinx.coroutines.flow.StateFlow

interface MediaPlayerAudioController {

    val volume: StateFlow<Float>
    val isMute: StateFlow<Boolean>
    val maxValue: Float

    fun toggleMute(mute: Boolean? = null)

    @UiThread
    fun setVolume(volume: Float)

    @UiThread
    fun volumeUp(value: Float = 0.05f)

    @UiThread
    fun volumeDown(value: Float = 0.05f)
}
