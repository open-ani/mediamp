/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.openani.mediamp.exoplayer

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.features.VideoEnhancement
import org.openani.mediamp.features.VideoEnhancementMode

/** Desktop-aligned Anime4K: Restore CNN S followed by sharp Lanczos viewport scaling. */
@OptIn(InternalForInheritanceMediampApi::class)
internal class ExoPlayerVideoEnhancement(
    private val player: ExoPlayer,
) : VideoEnhancement {
    override val mode: MutableStateFlow<VideoEnhancementMode> =
        MutableStateFlow(VideoEnhancementMode.OFF)

    override val supportedModes: Set<VideoEnhancementMode> = VideoEnhancementMode.entries.toSet()

    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0
    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0
    private var appliedWidth: Int = 0
    private var appliedHeight: Int = 0
    private var enhancementApplied: Boolean = false
    private var scalerApplied: Boolean = false

    override fun setMode(mode: VideoEnhancementMode) {
        require(mode in supportedModes) { "Unsupported video enhancement mode: $mode" }
        if (this.mode.value == mode) return
        this.mode.value = mode
        applyEffectiveMode()
    }

    fun updateSourceSize(width: Int, height: Int) {
        if (sourceWidth == width && sourceHeight == height) return
        sourceWidth = width.coerceAtLeast(0)
        sourceHeight = height.coerceAtLeast(0)
        applyEffectiveMode()
    }

    fun updateViewportSize(width: Int, height: Int) {
        if (viewportWidth == width && viewportHeight == height) return
        viewportWidth = width.coerceAtLeast(0)
        viewportHeight = height.coerceAtLeast(0)
        applyEffectiveMode()
    }

    private fun applyEffectiveMode() {
        val shouldApply = mode.value == VideoEnhancementMode.CLEAR
        if (!shouldApply) {
            if (enhancementApplied) {
                player.setVideoEffects(emptyList())
                enhancementApplied = false
                scalerApplied = false
                appliedWidth = 0
                appliedHeight = 0
            }
            return
        }
        val shouldApplyScaler = sourceWidth > 0 && sourceHeight > 0 &&
            viewportWidth > 0 && viewportHeight > 0
        if (
            enhancementApplied && scalerApplied == shouldApplyScaler &&
            (!shouldApplyScaler || appliedWidth == viewportWidth && appliedHeight == viewportHeight)
        ) return

        player.setVideoEffects(
            buildList {
                add(Anime4kRestoreEffect)
                if (shouldApplyScaler) {
                    add(DesktopStyleLanczosSharpEffect(viewportWidth, viewportHeight))
                }
            },
        )
        enhancementApplied = true
        scalerApplied = shouldApplyScaler
        appliedWidth = if (shouldApplyScaler) viewportWidth else 0
        appliedHeight = if (shouldApplyScaler) viewportHeight else 0
    }
}
