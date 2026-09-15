/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.utils

import org.jetbrains.skiko.SkiaLayer
import java.lang.reflect.Method

/**
 * A Skia layer and reflective access to its live redrawer. Only the reflection metadata
 * is cached: Skiko can replace the redrawer after a renderer fallback or display change.
 */
internal class SkiaLayerRedrawer(val layer: SkiaLayer) {
    val redrawerOrNull: Any?
        get() = getRedrawerMethod.invoke(layer)

    val redrawer: Any
        get() = redrawerOrNull ?: error(
            "SkiaLayer has no redrawer yet. Attach the player after the Compose window is visible.",
        )

    companion object {
        private val getRedrawerMethod: Method = SkiaLayer::class.java.getMethod("getRedrawer\$skiko")
    }
}
