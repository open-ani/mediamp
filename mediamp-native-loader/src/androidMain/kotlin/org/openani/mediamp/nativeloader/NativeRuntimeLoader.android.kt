/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.nativeloader

import java.io.File

@Suppress("UNUSED_PARAMETER")
internal actual fun loadRuntimeWrapperLibrary(runtimeDir: File, libraryName: String) {
    System.loadLibrary(libraryName)
}
