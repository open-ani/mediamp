/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeTeardownSequenceTest {
    @Test
    fun `prepare failure still stops but preserves handle`() {
        val calls = mutableListOf<String>()
        var failure: Throwable? = null

        val destroyed = runNativeTeardownSequence(
            prepare = {
                calls += "prepare"
                error("graphics teardown failed")
            },
            stop = { calls += "stop" },
            destroy = { calls += "destroy" },
            close = { calls += "close" },
            onPrepareFailure = { failure = it },
        )

        assertFalse(destroyed)
        assertEquals(listOf("prepare", "stop"), calls)
        assertIs<IllegalStateException>(failure)
    }

    @Test
    fun `successful prepare runs complete teardown in order`() {
        val calls = mutableListOf<String>()

        val destroyed = runNativeTeardownSequence(
            prepare = { calls += "prepare" },
            stop = { calls += "stop" },
            destroy = { calls += "destroy" },
            close = { calls += "close" },
            onPrepareFailure = { throw AssertionError("unexpected failure", it) },
        )

        assertTrue(destroyed)
        assertEquals(listOf("prepare", "stop", "destroy", "close"), calls)
    }

    @Test
    fun `failure reporter cannot prevent stop`() {
        val calls = mutableListOf<String>()

        val destroyed = runNativeTeardownSequence(
            prepare = { error("graphics teardown failed") },
            stop = { calls += "stop" },
            destroy = { calls += "destroy" },
            close = { calls += "close" },
            onPrepareFailure = { error("logging failed") },
        )

        assertFalse(destroyed)
        assertEquals(listOf("stop"), calls)
    }
}
