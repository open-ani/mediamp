/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.utils

import com.sun.net.httpserver.HttpServer
import org.openani.mediamp.mpv.MpvMediampPlayer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Shared fixtures for the real-libmpv tests: locating the native runtime, generating test clips
 * with ffmpeg, and serving them over HTTP.
 */
internal object MpvTestMedia {
    fun devNativeDir(): File? =
        System.getProperty("mediamp.mpv.dev.native.dir")
            ?.let(::File)
            ?.takeIf {
                it.resolve("libmediampv.dylib").isFile || it.resolve("libmediampv.so").isFile ||
                        it.resolve("mediampv.dll").isFile
            }

    /**
     * Logs why a test is skipped and returns `false`, or fails under
     * `mediamp.mpv.test.required=true`.
     */
    fun skip(tag: String, reason: String): Boolean {
        System.err.println("[$tag] setup skipped: $reason")
        check(System.getProperty("mediamp.mpv.test.required") != "true") {
            "$tag is required on this runner but would be skipped: $reason"
        }
        return false
    }

    /**
     * Prepares the native runtime, or returns `false` with the reason logged. Under
     * `mediamp.mpv.test.required=true` a missing environment fails instead of skipping, so the
     * suite cannot silently degrade into a no-op on the runner that is expected to have it.
     */
    fun prepareOrSkip(tag: String): Boolean {
        fun skip(reason: String): Boolean = skip(tag, reason)

        val osName = System.getProperty("os.name")
        if (!osName.contains("Mac") && !osName.contains("Windows")) {
            return skip("no desktop render path on $osName")
        }
        val dir = devNativeDir()
            ?: return skip(
                "dev native dir not usable " +
                        "(mediamp.mpv.dev.native.dir=${System.getProperty("mediamp.mpv.dev.native.dir")})",
            )
        runCatching { MpvMediampPlayer.prepareLibraries(dir.absolutePath, extractRuntimeLibrary = false) }
            .onFailure { return skip("prepareLibraries failed: $it") }
        return true
    }

    fun findFfmpeg(): String? =
        listOfNotNull(
            devNativeDir()?.resolve("ffmpeg.exe")?.absolutePath,
            "/opt/homebrew/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
            "ffmpeg",
            "ffmpeg.exe",
        )
            .firstOrNull { runCatching { ProcessBuilder(it, "-version").start().waitFor() }.getOrNull() == 0 }

    /**
     * An audio+video test clip of [seconds] seconds with the `moov` atom up front
     * (`faststart`), so mpv can start decoding from a sequential HTTP read without first
     * seeking to the file end. Cached in the temp directory across runs. `null` when ffmpeg is
     * unavailable or generation fails.
     */
    fun generateClip(seconds: Int): File? {
        val target = File(System.getProperty("java.io.tmpdir"), "mediamp-mpv-test-clip-${seconds}s.mp4")
        if (target.isFile && target.length() > 0) return target
        val ffmpeg = findFfmpeg() ?: return null
        val process = ProcessBuilder(
            ffmpeg, "-y",
            "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-t", seconds.toString(), "-c:v", "mpeg4", "-q:v", "5", "-c:a", "aac",
            "-movflags", "+faststart",
            target.absolutePath,
        ).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        return target
    }
}

/**
 * Static file server with HTTP range support, so mpv treats the file as a seekable network
 * stream (stream cache on). Requests are served concurrently: mpv keeps one connection
 * streaming while probing other byte ranges on another.
 *
 * @param bytesPerSecond optional bandwidth limit per connection, to keep a clip from being
 *   fully cached before a test has observed the partially-buffered states.
 */
internal class RangeFileServer(
    private val file: File,
    private val bytesPerSecond: Long? = null,
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "mpv-test-http").apply { isDaemon = true }
    }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = this@RangeFileServer.executor
        createContext("/clip.mp4") { exchange ->
            val length = file.length()
            val range = exchange.requestHeaders.getFirst("Range")
                ?.removePrefix("bytes=")
                ?.split("-", limit = 2)
            val start = range?.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = range?.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (length - 1)
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            val count = end - start + 1
            if (range != null) {
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$length")
                exchange.sendResponseHeaders(206, count)
            } else {
                exchange.sendResponseHeaders(200, count)
            }
            try {
                if (exchange.requestMethod != "HEAD") {
                    file.inputStream().use { input ->
                        input.skipNBytes(start)
                        val buffer = ByteArray(CHUNK_BYTES)
                        var remaining = count
                        exchange.responseBody.use { out ->
                            while (remaining > 0) {
                                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                out.flush()
                                remaining -= read
                                bytesPerSecond?.let { Thread.sleep(read * 1000L / it) }
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                // mpv closes connections it no longer needs (e.g. after a seek); that is not a
                // server failure.
            } finally {
                exchange.close()
            }
        }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}/clip.mp4"

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private companion object {
        const val CHUNK_BYTES = 16 * 1024
    }
}
