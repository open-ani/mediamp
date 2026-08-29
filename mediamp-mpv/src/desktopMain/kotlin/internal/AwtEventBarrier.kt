/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import java.awt.EventQueue

/**
 * Runs [block] on the AWT event thread after all events already queued before this call.
 *
 * Skiko's Linux frame dispatcher draws and swaps on this thread. Consequently, a caller
 * from a native-teardown thread can use this as a completion barrier for an in-flight
 * frame and perform GLX destruction without racing Mesa's swap path.
 */
internal fun runOnAwtEventThreadAndWait(block: () -> Unit) {
    if (EventQueue.isDispatchThread()) {
        block()
    } else {
        EventQueue.invokeAndWait(block)
    }
}
