/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import java.awt.EventQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AwtEventBarrierTest {
    @Test
    fun `runs on event thread after previously queued work`() {
        val events = CopyOnWriteArrayList<String>()
        EventQueue.invokeLater { events += "frame" }

        runOnAwtEventThreadAndWait {
            assertTrue(EventQueue.isDispatchThread())
            events += "teardown"
        }

        assertEquals(listOf("frame", "teardown"), events)
    }

    @Test
    fun `runs inline when already on event thread`() {
        EventQueue.invokeAndWait {
            val eventThread = Thread.currentThread()
            runOnAwtEventThreadAndWait {
                assertEquals(eventThread, Thread.currentThread())
            }
        }
    }
}
