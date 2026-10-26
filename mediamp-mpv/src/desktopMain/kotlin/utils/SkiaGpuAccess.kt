/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.utils

/**
 * Skiko records Compose pictures on the EDT, but Metal/Direct3D replay them on a
 * background thread. GrDirectContext is not thread safe: even closing a video
 * snapshot can mutate the resource cache while that other thread is drawing.
 *
 * Use the renderer's own monitor, not a per-player lock: every player in a window
 * shares its context, and Skiko's draw and context disposal must be excluded too.
 * Renderers without a drawLock execute their GPU work on the EDT.
 */
internal class SkiaGpuAccess(val renderer: Any) {
    private val monitor = renderer.javaClass.skiaFieldOrNull("drawLock")?.get(renderer)

    init {
        check(monitor != null || renderer.javaClass.name !in asynchronousRenderers) {
            "Unsupported asynchronous Skiko renderer ${renderer.javaClass.name}: no drawLock"
        }
    }

    fun <T> withLock(action: () -> T): T =
        if (monitor == null) action() else synchronized(monitor, action)

    companion object {
        private val asynchronousRenderers = setOf(
            "org.jetbrains.skiko.redrawer.MetalRedrawer",
            "org.jetbrains.skiko.redrawer.Direct3DRedrawer",
            "org.jetbrains.skiko.renderer.MetalRenderer",
            "org.jetbrains.skiko.renderer.Direct3DRenderer",
        )
    }
}
