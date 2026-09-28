/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.utils

import org.jetbrains.skia.DirectContext

/**
 * Interop for readback consumers that draw raster images: they need neither Skia's
 * render device nor its DirectContext, so nothing is read from the redrawer.
 */
internal object SkiaReadbackInterop : SkiaRenderDeviceInterop {
    override val renderDevicePtr: Long get() = 0L
    override val directContext: DirectContext? get() = null
}
