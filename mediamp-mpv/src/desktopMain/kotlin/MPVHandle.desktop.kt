/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */
@file:JvmName("MPVHandleDesktop")

package org.openani.mediamp.mpv

import org.openani.mediamp.InternalMediampApi

@InternalMediampApi
external fun nAttachDesktopBufferRenderer(ptr: Long, renderer: MpvBufferRenderer): Boolean

@InternalMediampApi
external fun nDetachDesktopBufferRenderer(ptr: Long): Boolean

internal actual fun attachSurface(ptr: Long, surface: Any): Boolean {
    require(surface is MpvBufferRenderer) { "surface must implement MpvBufferRenderer" }
    return nAttachDesktopBufferRenderer(ptr, surface)
}

internal actual fun detachSurface(ptr: Long): Boolean {
    return nDetachDesktopBufferRenderer(ptr)
}
