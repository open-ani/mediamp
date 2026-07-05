/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpvdemo

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin

class MpvPlayer(hwdec: String = System.getProperty("mpvdemo.hwdec") ?: "videotoolbox") {
    private val ctx: Long = MpvNative.create(hwdec).also {
        check(it != 0L) { "Failed to create mpv player context, see stderr for details" }
    }

    var skiaSurface: Surface? = null
        private set
    private var renderTarget: BackendRenderTarget? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    @Volatile
    private var closed = false

    fun loadFile(path: String) {
        MpvNative.setPropertyString(ctx, "loop-file", "inf")
        MpvNative.command(ctx, arrayOf("loadfile", path))
    }

    fun setUpdateListener(listener: MpvNative.UpdateListener?) {
        if (!closed) MpvNative.setUpdateListener(ctx, listener)
    }

    /** (Re)creates the IOSurface/FBO/MTLTexture chain if the composable size changed. */
    fun ensureSurface(width: Int, height: Int, mtlDevicePtr: Long, directContext: DirectContext): Boolean {
        if (closed) return false
        if (width == surfaceWidth && height == surfaceHeight && skiaSurface != null) return true

        releaseSurface()
        val mtlTexture = MpvNative.createSurface(ctx, width, height, mtlDevicePtr)
        if (mtlTexture == 0L) return false

        val target = BackendRenderTarget.makeMetal(width, height, mtlTexture)
        skiaSurface = Surface.makeFromBackendRenderTarget(
            context = directContext,
            rt = target,
            origin = SurfaceOrigin.TOP_LEFT,
            colorFormat = SurfaceColorFormat.BGRA_8888,
            colorSpace = ColorSpace.sRGB,
        )
        renderTarget = target
        surfaceWidth = width
        surfaceHeight = height
        return skiaSurface != null
    }

    fun renderFrame() {
        if (!closed) MpvNative.renderFrame(ctx)
    }

    fun releaseSurface() {
        skiaSurface?.close()
        skiaSurface = null
        renderTarget?.close()
        renderTarget = null
        surfaceWidth = 0
        surfaceHeight = 0
    }

    fun togglePause() {
        val paused = MpvNative.getPropertyString(ctx, "pause") == "yes"
        MpvNative.setPropertyString(ctx, "pause", if (paused) "no" else "yes")
    }

    fun seekAbsolute(seconds: Double) {
        MpvNative.command(ctx, arrayOf("seek", seconds.toString(), "absolute"))
    }

    val isPaused: Boolean get() = MpvNative.getPropertyString(ctx, "pause") == "yes"
    val timePos: Double get() = MpvNative.getPropertyDouble(ctx, "time-pos")
    val duration: Double get() = MpvNative.getPropertyDouble(ctx, "duration")
    val hwdecCurrent: String? get() = MpvNative.getPropertyString(ctx, "hwdec-current")
    val videoFormat: String? get() = MpvNative.getPropertyString(ctx, "video-format")
    val estimatedVfFps: Double get() = MpvNative.getPropertyDouble(ctx, "estimated-vf-fps")

    fun close() {
        if (closed) return
        closed = true
        releaseSurface()
        MpvNative.destroy(ctx)
    }
}
