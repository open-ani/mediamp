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
 * Runs [block] on the AWT event thread.
 *
 * Off the event thread, this waits for events already queued before this call and therefore
 * acts as a completion barrier for an in-flight Skiko frame. On the event thread it runs
 * inline and provides no ordering guarantee for events queued after the current event.
 */
internal fun runOnAwtEventThreadAndWait(block: () -> Unit) {
    if (EventQueue.isDispatchThread()) {
        block()
    } else {
        EventQueue.invokeAndWait(block)
    }
}
