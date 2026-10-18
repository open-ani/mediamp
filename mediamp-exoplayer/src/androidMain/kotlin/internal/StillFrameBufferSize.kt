/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.internal

import android.view.SurfaceView
import androidx.media3.common.Player
import androidx.media3.common.Timeline

/**
 * Keeps the buffer of [surfaceView] at a fixed size while it shows a frame that playback is not
 * replacing (paused, buffering, ended), so that a layout change scales that frame instead of
 * cutting it.
 *
 * Under video effects, media3 renders frames through a GL pipeline sized to the output surface,
 * and nothing re-renders the last frame when the surface is resized while no frames flow. A
 * SurfaceView whose buffer follows the layout then shows that frame unscaled at the top-left of
 * the new buffer until the next frame arrives: entering fullscreen while paused leaves the video
 * squeezed into the left part of the screen, leaving it crops the video. With a fixed buffer
 * size, SurfaceView scales the buffer to its bounds. The buffer follows the layout while playback
 * runs, so new frames render at the view's resolution.
 *
 * Until a frame of the current media is rendered there is nothing to keep, and the buffer
 * follows the layout, so that the first frame renders for the view's final shape: the effects
 * pipeline fits each frame into the buffer, and a buffer of another aspect ratio would leave
 * that frame distorted once SurfaceView scales it.
 */
internal class StillFrameBufferSize(
    private val surfaceView: SurfaceView,
    private val player: Player,
) : Player.Listener {
    private var frameRendered = false

    init {
        player.addListener(this)
        update()
    }

    fun release() {
        player.removeListener(this)
        surfaceView.holder.setSizeFromLayout()
    }

    override fun onRenderedFirstFrame() {
        frameRendered = true
        update()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            frameRendered = false
            update()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) = update()

    private fun update() {
        val holder = surfaceView.holder
        if (frameRendered && !player.isPlaying) {
            // While the buffer is fixed, surfaceFrame stays at the fixed size, so repeated calls
            // keep the size the retained frame was rendered for.
            val frame = holder.surfaceFrame
            if (frame.width() > 0 && frame.height() > 0) {
                holder.setFixedSize(frame.width(), frame.height())
            }
        } else {
            holder.setSizeFromLayout()
        }
    }
}
