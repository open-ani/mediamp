/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import android.content.Context
import java.io.File
import org.openani.mediamp.nativeloader.NativeRuntimeLoader

internal actual object LibraryLoader {
    actual fun setRuntimeLibraryDirectory(path: String, extractRuntimeLibrary: Boolean) {
    }

    actual fun useDefaultRuntimeLibraryDirectory() {
    }

    actual fun loadLibraries(context: Any?) {
        val appContext = (context as? Context)?.applicationContext
            ?: error("Android mpv loading requires an android.content.Context.")
        val runtimeDir = File(appContext.applicationInfo.nativeLibraryDir)
        NativeRuntimeLoader.ensureRuntimeLoaded(runtimeDir, "mediampv")
    }
}
