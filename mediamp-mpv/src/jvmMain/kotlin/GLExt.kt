/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

internal object GLExt {
    init {
        LibraryLoader.loadLibraries()
    }

    external fun glViewport(x: Int, y: Int, width: Int, height: Int)
}
