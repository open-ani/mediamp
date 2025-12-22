/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

internal object OffscreenGL {
    init {
        LibraryLoader.loadLibraries()
    }

    external fun createTextureFbo(width: Int, height: Int): Long
    external fun getFboId(ptr: Long): Int
    external fun disposeTextureFbo(ptr: Long)
}
