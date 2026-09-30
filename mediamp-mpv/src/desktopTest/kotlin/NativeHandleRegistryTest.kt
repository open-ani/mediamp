/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import org.openani.mediamp.mpv.internal.headlessSurfaceBackend
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Native players are addressed by registry ids (handle_registry.cpp), not addresses:
 * calls with an id whose player was closed must find nothing instead of freed memory,
 * and ids must never be reused.
 */
class NativeHandleRegistryTest {

    private fun prepareOrSkip(): Boolean {
        val dir = System.getProperty("mediamp.mpv.dev.native.dir")
            ?.let(::File)
            ?.takeIf {
                it.resolve("mediampv.dll").isFile || it.resolve("libmediampv.dylib").isFile ||
                    it.resolve("libmediampv.so").isFile
            }
        if (dir == null || headlessSurfaceBackend() == null) {
            System.err.println("[NativeHandleRegistryTest] skipped: no native runtime or desktop backend")
            check(System.getProperty("mediamp.mpv.test.required") != "true") {
                "mpv native tests are required on this runner but would be skipped"
            }
            return false
        }
        MpvMediampPlayer.prepareLibraries(dir.absolutePath, extractRuntimeLibrary = false)
        return true
    }

    @Test
    fun `a closed player's id resolves to nothing`() {
        if (!prepareOrSkip()) return
        val backend = headlessSurfaceBackend()!!
        val handle = MPVHandle(Any())
        val id = handle.ptr
        handle.close()

        assertFalse(backend.hasSurface(id))
        assertFalse(backend.setSurfaceConfig(id, 64, 64, 0L))
        // "No frame" for a missing instance: 0, or the no-buffer sentinel on Linux.
        val state = backend.getFrameState(id)
        assertTrue(state == 0L || state == (0xFL shl 44), "unexpected frame state $state for a closed player")
    }

    @Test
    fun `ids are never reused`() {
        if (!prepareOrSkip()) return
        val ids = List(5) {
            val handle = MPVHandle(Any())
            handle.ptr.also { handle.close() }
        }
        assertTrue(ids.all { it > 0 }, "ids must be positive: $ids")
        assertEquals(ids.size, ids.toSet().size, "ids were reused: $ids")
    }

    @Test
    fun `closing while another thread calls in does not crash`() {
        if (!prepareOrSkip()) return
        val backend = headlessSurfaceBackend()!!
        repeat(20) {
            val handle = MPVHandle(Any())
            val id = handle.ptr
            val started = CountDownLatch(1)
            val stop = AtomicBoolean(false)
            val caller = thread {
                started.countDown()
                while (!stop.get()) {
                    backend.getFrameState(id)
                    backend.hasSurface(id)
                }
            }
            started.await()
            handle.close()
            stop.set(true)
            caller.join()
            assertFalse(backend.hasSurface(id))
        }
    }
}
