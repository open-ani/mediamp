package org.openani.mediamp.mpv

import org.openani.mediamp.internal.Platform
import org.openani.mediamp.internal.currentPlatform

internal object LibraryLoader {
    fun loadLibraries() {
        when (currentPlatform()) {
            is Platform.Android, is Platform.Windows -> System.loadLibrary("mediampv")
            else -> {}
        }
    }
}
