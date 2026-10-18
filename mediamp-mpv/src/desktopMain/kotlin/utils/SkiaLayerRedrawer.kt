/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.skiko.SkiaLayer
import java.util.WeakHashMap

/**
 * A Skia layer and reflective access to its live redrawer. Only the reflection metadata
 * is cached: Skiko can replace the redrawer after a renderer fallback or display change.
 */
internal class SkiaLayerRedrawer(val layer: SkiaLayer) {
    val redrawerOrNull: Any?
        get() = rendererAccess.currentRenderer(layer)

    val redrawer: Any
        get() = redrawerOrNull ?: error(
            "SkiaLayer has no redrawer yet. Attach the player after the Compose window is visible.",
        )

    /**
     * Counts Skiko's renderer switches on this layer: startup fallbacks, the fallback after a
     * redrawer fails its first frame (`SkiaLayer.inDrawScope`), and later recreations. It
     * changes while Skiko is still installing the next redrawer, so read [redrawerOrNull]
     * after a dispatch, not from the notification itself.
     */
    val rendererChanges: StateFlow<Long> get() = rendererChangesOf(layer)

    companion object {
        private val rendererAccess = SkiaRendererAccess(SkiaLayer::class.java)

        // Skiko listeners cannot be removed, so each layer gets exactly one, shared by every
        // surface on it. The counter does not reference the layer, which stays collectable.
        private val rendererChangeCounters = WeakHashMap<SkiaLayer, MutableStateFlow<Long>>()

        private fun rendererChangesOf(layer: SkiaLayer): StateFlow<Long> = synchronized(rendererChangeCounters) {
            rendererChangeCounters.getOrPut(layer) {
                MutableStateFlow(0L).also { counter ->
                    layer.onStateChanged(SkiaLayer.PropertyKind.Renderer) { counter.update { it + 1 } }
                }
            }
        }
    }
}
