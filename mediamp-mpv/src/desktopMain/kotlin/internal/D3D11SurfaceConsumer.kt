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
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.nCanShareFramesD3D11

/**
 * Consumer of [D3D11SurfaceRingBackend]. The ring's shared textures open only on a D3D12
 * device of the adapter mpv's D3D11 device was created on. mpv's device follows Skia's
 * adapter, but it can end up elsewhere: created on the default adapter or WARP because
 * Skia's adapter refused it, or left behind when Skiko recreated its redrawer on another
 * GPU (the device is fixed for the render context's lifetime). Sharing then fails and the
 * video stays black, so for such a device this consumer takes frames through the same
 * render context's CPU readback mode ([D3D11ReadbackSurfaceBackend], drawn by
 * [MpvReadbackSurface]) instead. Device 0 (headless) keeps the ring, which needs no Skia
 * side.
 *
 * The producer stays the same in both modes, so switching works during playback, unlike
 * replacing the backend.
 *
 * All methods are called from the Compose/Skiko UI thread only.
 */
@OptIn(InternalMediampApi::class)
internal class D3D11SurfaceConsumer(private val handlePtr: Long) : MpvSurfaceConsumer {
    private val ring = MpvSurfaceRing(handlePtr, D3D11SurfaceRingBackend)
    private val readback = MpvReadbackSurface(handlePtr, D3D11ReadbackSurfaceBackend)
    private var active: MpvSurfaceConsumer? = null

    /** The device [active] was chosen for; null until a request with it succeeded. */
    private var chosenForDevicePtr: Long? = null

    private var requestedWidth = 0
    private var requestedHeight = 0

    override val rendererName: String get() = (active ?: ring).rendererName

    private fun consumerFor(devicePtr: Long): MpvSurfaceConsumer =
        if (devicePtr == 0L || nCanShareFramesD3D11(handlePtr, devicePtr)) ring else readback

    override fun requestSurface(width: Int, height: Int, devicePtr: Long): Boolean {
        val current = active
        val target = if (current != null && devicePtr == chosenForDevicePtr) current else consumerFor(devicePtr)
        if (target !== current) {
            // The outgoing mode drops its Skia objects first; the producer then frees its
            // ring when it applies the new config (see surface_ring.h).
            current?.release()
            active = target
        }
        if (!target.requestSurface(width, height, devicePtr)) {
            chosenForDevicePtr = null // choose again on the next request
            return false
        }
        chosenForDevicePtr = devicePtr
        requestedWidth = width
        requestedHeight = height
        return true
    }

    override fun refreshDeviceIfChanged(devicePtr: Long) {
        if (requestedWidth > 0 && devicePtr != chosenForDevicePtr) {
            requestSurface(requestedWidth, requestedHeight, devicePtr)
        }
    }

    override fun invalidateForRenderEnvironmentChange() {
        active?.invalidateForRenderEnvironmentChange()
    }

    override fun currentFrameImage(directContext: DirectContext?): Image? =
        active?.currentFrameImage(directContext)

    override fun release() {
        active?.release()
        active = null
        chosenForDevicePtr = null
        requestedWidth = 0
        requestedHeight = 0
    }
}
