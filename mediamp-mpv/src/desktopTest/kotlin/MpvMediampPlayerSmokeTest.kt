/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration smoke test against a real libmpv (Homebrew) using the dev-native JNI build.
 * Skipped when the native runtime or ffmpeg (for test video generation) is unavailable.
 */
class MpvMediampPlayerSmokeTest {

    private fun devNativeDir(): File? =
        System.getProperty("mediamp.mpv.dev.native.dir")
            ?.let(::File)
            ?.takeIf { it.resolve("libmediampv.dylib").isFile || it.resolve("libmediampv.so").isFile }

    private fun prepareOrSkip(): Boolean {
        if (!System.getProperty("os.name").contains("Mac")) return false
        val dir = devNativeDir() ?: return false
        runCatching { MpvMediampPlayer.prepareLibraries(dir.absolutePath, extractRuntimeLibrary = false) }
            .onFailure { return false }
        return true
    }

    private fun generateTestVideo(): File? {
        val target = File(System.getProperty("java.io.tmpdir"), "mediamp-mpv-test-video.mp4")
        if (target.isFile && target.length() > 0) return target
        val ffmpeg = listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "ffmpeg")
            .firstOrNull { runCatching { ProcessBuilder(it, "-version").start().waitFor() }.getOrNull() == 0 }
            ?: return null
        val process = ProcessBuilder(
            ffmpeg, "-y",
            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30,format=yuv420p",
            "-t", "5", "-c:v", "libx264", "-preset", "ultrafast",
            target.absolutePath,
        ).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        return target
    }

    /**
     * With vo=libmpv, frames are only consumed when a render context drains them; without
     * one, playback never advances. This drives the real macOS Metal render path headlessly
     * (device ptr 0 = system default MTLDevice).
     */
    @OptIn(InternalMediampApi::class)
    private fun startHeadlessRenderer(player: MpvMediampPlayer): AutoCloseable {
        val handle = player.impl as MPVHandle
        check(player.createMacosRenderContext()) { "createMacosRenderContext failed" }
        check(nCreateMetalSurface(handle.ptr, 640, 360, 0L) != 0L) { "nCreateMetalSurface failed" }
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val thread = Thread {
            while (running.get()) {
                nRenderFrameMacos(handle.ptr)
                Thread.sleep(15)
            }
        }.apply { isDaemon = true; start() }
        return AutoCloseable {
            running.set(false)
            thread.join(2000)
            nReleaseMetalSurface(handle.ptr)
            player.releaseMacosRenderContext()
        }
    }

    private suspend fun StateFlow<PlaybackState>.await(state: PlaybackState, timeoutMillis: Long = 10_000) {
        withTimeout(timeoutMillis) {
            collectUntil { it == state }
        }
    }

    private suspend fun <T> StateFlow<T>.collectUntil(predicate: (T) -> Boolean) {
        if (predicate(value)) return
        var done = false
        collectWhile { value ->
            if (predicate(value)) done = true
            !done
        }
    }

    private suspend fun <T> StateFlow<T>.collectWhile(predicate: (T) -> Boolean) {
        try {
            collect { if (!predicate(it)) throw StopCollecting }
        } catch (e: StopCollecting) {
            // done
        }
    }

    private object StopCollecting : Exception() {
        private fun readResolve(): Any = StopCollecting
    }

    @OptIn(InternalMediampApi::class, ExperimentalMediampApi::class)
    @Test
    fun `uri playback - state machine, seek, features`() {
        if (!prepareOrSkip()) return
        val video = generateTestVideo() ?: return

        runBlocking(Dispatchers.Default) {
            val player = MpvMediampPlayer(Any(), coroutineContext)
            val renderer = startHeadlessRenderer(player)
            try {
                player.setMediaData(UriMediaData(video.absolutePath, emptyMap(), MediaExtraFiles.EMPTY))
                assertEquals(PlaybackState.READY, player.playbackState.value)

                player.resume()
                player.playbackState.await(PlaybackState.PLAYING)

                // Position should advance.
                withTimeout(10_000) {
                    player.currentPositionMillis.collectUntil { it > 200 }
                }
                val handle = player.impl as MPVHandle
                assertNotNull(handle.getPropertyString("hwdec-current"), "hwdec-current should be queryable")

                // Metadata: at least one audio-less video track list refresh happened without crash.
                val metadata = player.features[MediaMetadata]
                assertNotNull(metadata)

                // Playback speed via feature.
                val speed = assertNotNull(player.features[PlaybackSpeed.Key])
                speed.set(1.5f)
                assertEquals(1.5f, speed.value)

                // Seek: optimistic position + eventual convergence.
                player.seekTo(3_000)
                assertEquals(3_000, player.getCurrentPositionMillis())
                withTimeout(10_000) {
                    player.currentPositionMillis.collectUntil { it in 2_500..5_500 }
                }

                player.pause()
                player.playbackState.await(PlaybackState.PAUSED)

                player.resume()
                player.playbackState.await(PlaybackState.PLAYING)

                player.stopPlayback()
                player.playbackState.await(PlaybackState.FINISHED)
            } finally {
                renderer.close()
                player.close()
            }
            player.playbackState.await(PlaybackState.DESTROYED, 5_000)
        }
    }

    @OptIn(InternalMediampApi::class)
    @Test
    fun `seekable input playback via stream_cb`() {
        if (!prepareOrSkip()) return
        val video = generateTestVideo() ?: return

        runBlocking(Dispatchers.Default) {
            val player = MpvMediampPlayer(Any(), coroutineContext)
            val renderer = startHeadlessRenderer(player)
            try {
                player.setMediaData(FileSeekableInputMediaData(video))
                assertEquals(PlaybackState.READY, player.playbackState.value)

                player.resume()
                player.playbackState.await(PlaybackState.PLAYING)
                withTimeout(10_000) {
                    player.currentPositionMillis.collectUntil { it > 200 }
                }

                // Seeking exercises stream_cb seek_fn.
                player.seekTo(2_000)
                withTimeout(10_000) {
                    player.currentPositionMillis.collectUntil { it in 1_500..4_500 }
                }

                player.stopPlayback()
                player.playbackState.await(PlaybackState.FINISHED)
            } finally {
                renderer.close()
                player.close()
            }
        }
    }

    @OptIn(ExperimentalMediampApi::class)
    private class FileSeekableInputMediaData(private val file: File) : SeekableInputMediaData {
        override val uri: String get() = "test://${file.name}"
        override val extraFiles: MediaExtraFiles get() = MediaExtraFiles.EMPTY
        override val options: List<String> get() = emptyList()
        override fun fileLength(): Long = file.length()
        override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput =
            RandomAccessFileSeekableInput(RandomAccessFile(file, "r"))

        override fun close() {}
    }

    private class RandomAccessFileSeekableInput(private val raf: RandomAccessFile) : SeekableInput {
        override val position: Long get() = raf.filePointer
        override val size: Long get() = raf.length()
        override val bytesRemaining: Long get() = size - position

        override fun seekTo(position: Long) {
            raf.seek(position)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            raf.read(buffer, offset, length)

        override fun close() = raf.close()
    }
}
