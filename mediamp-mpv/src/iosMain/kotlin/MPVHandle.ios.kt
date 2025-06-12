/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

internal actual fun attachSurface(ptr: Long, surface: Any): Boolean {
    TODO("Not yet implemented")
}

internal actual fun detachSurface(ptr: Long): Boolean {
    TODO()
}

actual fun createRenderContext(ptr: Long): Boolean {
    error("only implemented on desktop")
}

actual fun destroyRenderContext(ptr: Long): Boolean {
    error("only implemented on desktop")
}

actual fun renderFrame(ptr: Long, fbo: Int, width: Int, height: Int): Boolean {
    error("only implemented on desktop")
}
