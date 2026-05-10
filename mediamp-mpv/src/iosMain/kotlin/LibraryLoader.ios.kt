/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

internal actual object LibraryLoader {
    actual fun setRuntimeLibraryDirectory(path: String, extractRuntimeLibrary: Boolean) {
    }

    actual fun useDefaultRuntimeLibraryDirectory() {
    }

    actual fun loadLibraries(context: Any?) {
        // iOS uses Kotlin/Native linkage and does not need JVM-style runtime extraction/loading.
    }
}
