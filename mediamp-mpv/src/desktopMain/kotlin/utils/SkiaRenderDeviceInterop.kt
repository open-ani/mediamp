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
 * Reflective access to the render device and Skia DirectContext that Skiko uses to
 * render a window. The native surface ring creates its consumer-side textures on this
 * device, and the Skia surfaces wrapping them must be created against the *current*
 * DirectContext.
 */
internal interface SkiaRenderDeviceInterop {
    /**
     * The device Skia renders with: an MTLDevice pointer on macOS
     * ([SkiaMetalInterop]), a pointer to Skiko's native DirectXDevice struct on
     * Windows ([SkiaDirectXInterop]).
     */
    val renderDevicePtr: Long

    /** Skia's *current* GrDirectContext; null until the first frame has been rendered. */
    val directContext: DirectContext?
}
