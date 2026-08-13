/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image

/**
 * Consumer side of a native render path: turns whatever the native render thread
 * publishes into a Skia [Image] the Compose surface draws. Two implementations exist:
 * [MpvSurfaceRing] wraps a ring of shared GPU textures (macOS Metal, Windows D3D11,
 * Linux GLX), and [MpvReadbackSurface] uploads the CPU frames of the Windows OpenGL
 * fallback. All methods are called from the Compose/Skiko UI thread only.
 */
internal interface MpvSurfaceConsumer {
    /**
     * Asks the native render thread to size its render target to [width] x [height]
     * (physical pixels, normally the composable size — mpv then scales, letterboxes
     * and renders subtitles at display resolution). Asynchronous and cheap; until the
     * first frame lands at the new size, [currentFrameImage] keeps returning the
     * previous frame. [devicePtr] is the consumer render device where the backend
     * needs one (see [MpvSurfaceBackend.setSurfaceConfig]).
     */
    fun requestSurface(width: Int, height: Int, devicePtr: Long): Boolean

    /** Re-posts the current config when Skiko recreated its redrawer on a new device. */
    fun refreshDeviceIfChanged(devicePtr: Long)

    /** Drops consumer-side Skia objects before the producer replaces its render environment. */
    fun invalidateForRenderEnvironmentChange()

    /**
     * Returns the latest video frame as a Skia image (safe to draw through Compose,
     * including RenderNode recordings; a GPU texture for the ring backends, an
     * immutable raster image for the readback fallback), or null when no frame exists
     * yet. Do NOT close the returned image — it is owned by this consumer.
     */
    fun currentFrameImage(directContext: DirectContext): Image?

    /** Releases all consumer resources and deactivates the native surface. */
    fun release()
}
