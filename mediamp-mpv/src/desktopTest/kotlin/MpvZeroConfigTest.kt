/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @OptIn(InternalMediampApi::class)
    @Test
    fun `http uri opens through bundled runtime with request headers`() = runBlocking {
        if (System.getProperty("mediamp.mpv.zeroconfig") != "true") {
            println("[ZeroConfigTest] skipped: run via :mediamp-mpv:zeroConfigTest")
            return@runBlocking
        }

        val requestSeen = CountDownLatch(1)
        val receivedHeader = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video.mp4") { exchange ->
            receivedHeader.set(exchange.requestHeaders.getFirst("X-Mediamp-Test"))
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            requestSeen.countDown()
        }
        server.start()

        try {
            val player = MpvMediampPlayer(Any(), Dispatchers.Default)
            try {
                player.setMediaData(
                    UriMediaData(
                        uri = "http://127.0.0.1:${server.address.port}/video.mp4",
                        headers = mapOf("X-Mediamp-Test" to "present"),
                        extraFiles = MediaExtraFiles.EMPTY,
                    ),
                )
                if (System.getProperty("os.name").contains("Linux", ignoreCase = true)) {
                    // Linux public playback intentionally waits for a live Skiko GLX
                    // environment before loadfile. This zero-config case only probes the
                    // bundled runtime's HTTP/header support against an intentional 404;
                    // the real public playback path is covered by the GLX validation lane.
                    assertTrue(
                        player.handle.command(
                            "loadfile",
                            "http://127.0.0.1:${server.address.port}/video.mp4",
                            "replace",
                        ),
                    )
                } else {
                    player.resume()
                }

                val didOpenHttpStream = withContext(Dispatchers.IO) {
                    requestSeen.await(10, TimeUnit.SECONDS)
                }
                assertTrue(didOpenHttpStream, "Bundled mpv runtime must open HTTP streams")
                assertEquals("present", receivedHeader.get())
            } finally {
                player.close()
            }
        } finally {
            server.stop(0)
        }
    }
}
