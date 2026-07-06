/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.Dispatchers
import org.openani.mediamp.InternalMediampApi
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies the zero-configuration contract that library consumers rely on:
 * with a `mediamp-mpv-runtime` jar on the classpath, creating a player must
 * auto-extract and load the natives — no [MpvMediampPlayer.prepareLibraries] call.
 *
 * Runs only via `:mediamp-mpv:zeroConfigTest`, which puts the platform runtime jar on
 * the classpath and runs in a fresh JVM (the loader keeps global state, so this must
 * not share a JVM with tests that call prepareLibraries explicitly).
 */
class MpvZeroConfigTest {

    @OptIn(InternalMediampApi::class)
    @Test
    fun `player loads natives from classpath without prepareLibraries`() {
        if (System.getProperty("mediamp.mpv.zeroconfig") != "true") {
            println("[ZeroConfigTest] skipped: run via :mediamp-mpv:zeroConfigTest")
            return
        }
        val player = MpvMediampPlayer(Any(), Dispatchers.Default)
        try {
            assertTrue((player.impl as MPVHandle).ptr != 0L, "MPVHandle must be created from classpath natives")
        } finally {
            player.close()
        }
    }
}
