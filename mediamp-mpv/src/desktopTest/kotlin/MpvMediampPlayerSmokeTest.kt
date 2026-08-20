/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.Screenshots
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration smoke test against a real libmpv (Homebrew) using the dev-native JNI build.
 * Skipped when the native runtime or ffmpeg (for test video generation) is unavailable.
 *
 * State assertions target the v2 model (`docs/playback-state-v2.md`): the atomic
 * [org.openani.mediamp.PlayerState] snapshots on `player.state`, plus edge facts on
 * `player.events`.
 */
class MpvMediampPlayerSmokeTest {

    private fun devNativeDir(): File? =
        System.getProperty("mediamp.mpv.dev.native.dir")
            ?.let(::File)
            ?.takeIf {
                it.resolve("libmediampv.dylib").isFile || it.resolve("libmediampv.so").isFile ||
                        it.resolve("mediampv.dll").isFile
            }

    /**
     * On runners that are expected to have the full environment (self-hosted macOS),
     * `-Pmediamp.mpv.test.required=true` turns silent skips into failures so the suite
     * cannot degrade into a permanently-green no-op.
     */
    private fun skip(reason: String): Boolean {
        // Print the reason unconditionally (stderr is always shown in CI) so that on a
        // required runner the failure reveals WHY setup was skipped, instead of only an
        // opaque IllegalStateException at this line.
        System.err.println("[SmokeTest] setup skipped: $reason")
        check(System.getProperty("mediamp.mpv.test.required") != "true") {
            "mpv smoke tests are required on this runner but would be skipped: $reason"
        }
        return false
    }

    private fun prepareOrSkip(): Boolean {
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

    private fun findFfmpeg(): String? =
        listOfNotNull(
            devNativeDir()?.resolve("ffmpeg.exe")?.absolutePath,
            "/opt/homebrew/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
            "ffmpeg",
            "ffmpeg.exe",
        )
            .firstOrNull { runCatching { ProcessBuilder(it, "-version").start().waitFor() }.getOrNull() == 0 }

    private fun runFfmpeg(vararg args: String): Boolean {
        val ffmpeg = findFfmpeg() ?: return false
        val process = ProcessBuilder(ffmpeg, "-y", *args)
            .redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        return process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0
    }

    private fun generateTestVideo(): File? {
        val target = File(System.getProperty("java.io.tmpdir"), "mediamp-mpv-test-video-mpeg4.mp4")
        if (target.isFile && target.length() > 0) return target
        val ok = runFfmpeg(
            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30,format=yuv420p",
            "-t", "5", "-c:v", "mpeg4", "-q:v", "2",
            target.absolutePath,
        )
        return target.takeIf { ok }
    }

    /**
     * 0.0s-2.5s solid red, 2.5s-5.0s solid blue — known pixel content for screenshot assertions.
     */
    private fun generateColorVideo(): File? {
        val target = File(System.getProperty("java.io.tmpdir"), "mediamp-mpv-test-colors-mpeg4.mp4")
        if (target.isFile && target.length() > 0) return target
        val ok = runFfmpeg(
            "-f", "lavfi", "-i", "color=c=red:size=640x360:rate=30:duration=2.5",
            "-f", "lavfi", "-i", "color=c=blue:size=640x360:rate=30:duration=2.5",
            "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0,format=yuv420p[v]",
            "-map", "[v]", "-c:v", "mpeg4", "-q:v", "2",
            target.absolutePath,
        )
        return target.takeIf { ok }
    }

    /**
     * Container with two SRT subtitle tracks. SRT requires the FFmpeg subrip decoder,
     * which the bundled FFmpeg build must include (open-ani/animeko#1128).
     */
    private fun generateDualSubtitleVideo(): File? {
        val tmpDir = File(System.getProperty("java.io.tmpdir"))
        val target = File(tmpDir, "mediamp-mpv-test-dualsub-mpeg4.mkv")
        if (target.isFile && target.length() > 0) return target
        val sub1 = File(tmpDir, "mediamp-mpv-test-sub1.srt")
        val sub2 = File(tmpDir, "mediamp-mpv-test-sub2.srt")
        sub1.writeText("1\n00:00:00,500 --> 00:00:09,000\nFirst subtitle track\n")
        sub2.writeText("1\n00:00:00,500 --> 00:00:09,000\nSecond subtitle track\n")
        val ok = runFfmpeg(
            "-f", "lavfi", "-i", "color=c=blue:size=320x240:rate=24:duration=10",
            "-i", sub1.absolutePath, "-i", sub2.absolutePath,
            "-map", "0:v", "-map", "1:s", "-map", "2:s",
            "-c:v", "mpeg4", "-q:v", "2", "-c:s", "srt",
            target.absolutePath,
        )
        return target.takeIf { ok }
    }

    /**
     * With vo=libmpv, frames are only consumed when a render context drains them; without
     * one, playback never advances. The native render thread does all rendering; this
     * just configures a headless buffer ring (device ptr 0 = system default MTLDevice on
     * macOS, a D3D11-only ring on Windows) so the real native render path is exercised.
     */
    @OptIn(InternalMediampApi::class)
    private fun startHeadlessRenderer(player: MpvMediampPlayer): AutoCloseable {
        check(player.createRenderContext()) { "createRenderContext failed" }
        check(player.requestSurface(640, 360, 0L)) { "requestSurface failed" }
        return AutoCloseable {
            player.releaseSurface()
            player.releaseRenderContext()
        }
    }

    /**
     * Runs [block] with a player confined to a dedicated single thread (standing in for
     * the UI thread — the block itself runs on it, so command-thread rules hold) and a
     * headless render surface.
     *
     * The dispatcher must own exactly one physical thread — `limitedParallelism(1)` only
     * serializes and may migrate between pool workers, while the machine checks command
     * threads by physical identity: a continuation resumed on a different worker (e.g. a
     * join completed from the IO release dispatcher) flips close() onto its asynchronous
     * trampoline, or trips checkMainThread, depending on scheduler luck (Windows CI flake).
     */
    private fun runPlayerTest(block: suspend CoroutineScope.(MpvMediampPlayer) -> Unit) {
        val mainDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "mpv-test-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        try {
            runBlocking(mainDispatcher) {
                val player = MpvMediampPlayer(
                    Any(), coroutineContext,
                    mainDispatcher = mainDispatcher,
                )
                val renderer = startHeadlessRenderer(player)
                try {
                    block(player)
                } finally {
                    renderer.close()
                    player.close()
                }
                // close() is terminal (spec: -> Released) and idempotent. Await rather than
                // assert synchronously: called off the machine thread, close() trampolines
                // doClose and Released commits asynchronously.
                withTimeout(10_000) { player.state.first { it.mediaStatus == MediaStatus.Released } }
                player.close()
                assertEquals(MediaStatus.Released, player.state.value.mediaStatus)
            }
        } finally {
            mainDispatcher.close()
        }
    }

    @OptIn(InternalMediampApi::class, ExperimentalMediampApi::class)
    @Test
    fun `uri playback - state machine, seek, features`() {
        if (!prepareOrSkip()) return
        val video = generateTestVideo() ?: run { skip("ffmpeg unavailable or test video generation failed"); return }

        runPlayerTest { player ->
            // Subscribe before issuing commands (events has no replay).
            val seekCompletions = mutableListOf<Long>()
            val eventsJob = launch(start = CoroutineStart.UNDISPATCHED) {
                player.events.filterIsInstance<PlaybackEvent.SeekCompleted>()
                    .collect { seekCompletions.add(it.positionMillis) }
            }

            player.setMediaData(UriMediaData(video.absolutePath, emptyMap(), MediaExtraFiles.EMPTY))
            // Open-in-setMediaData: Ready committed, default intent is paused.
            assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
            assertFalse(player.state.value.playWhenReady)

            player.play()
            // Intent flips synchronously; the clock advancing follows.
            assertTrue(player.state.value.playWhenReady)
            withTimeout(10_000) { player.state.first { it.isPlaying } }

            // Position should advance.
            withTimeout(10_000) { player.currentPositionMillis.first { it > 200 } }
            val handle = player.impl as MPVHandle
            assertNotNull(handle.getPropertyString("hwdec-current"), "hwdec-current should be queryable")

            // Duration must be known after the Ready point (never a negative sentinel).
            val duration = assertNotNull(player.mediaProperties.value?.durationMillis)
            assertTrue(duration in 4_000..6_000, "expected ~5s duration, got $duration")

            // Metadata: at least one audio-less video track list refresh happened without crash.
            val metadata = player.features[MediaMetadata]
            assertNotNull(metadata)

            // Playback speed through the machine.
            val speed = assertNotNull(player.features[PlaybackSpeed.Key])
            speed.set(1.5f)
            assertEquals(1.5f, speed.value)

            // Seek: optimistic position, SeekCompleted event, eventual convergence.
            player.seekTo(3_000)
            assertEquals(3_000, player.currentPositionMillis.value)
            withTimeout(10_000) { player.currentPositionMillis.first { it in 2_500..5_500 } }
            withTimeout(10_000) {
                while (seekCompletions.isEmpty()) delay(50)
            }

            // pause() flips the intent synchronously and applies it natively
            // (read-after-command) before returning.
            player.pause()
            assertFalse(player.state.value.playWhenReady)
            assertTrue(handle.getPropertyBoolean("pause"), "pauseImpl must have applied 'pause' natively")

            player.play()
            withTimeout(10_000) { player.state.first { it.isPlaying } }

            // stopPlayback unloads to Idle (v2: never Ended) and releases the media.
            player.stopPlayback()
            assertEquals(MediaStatus.Idle, player.state.value.mediaStatus)
            assertNull(player.mediaData.value)
            assertEquals(0L, player.currentPositionMillis.value)

            eventsJob.cancel()
        }
    }

    @OptIn(InternalMediampApi::class)
    @Test
    fun `player exposes video dimensions in media properties`() {
        if (!prepareOrSkip()) return
        val video = generateTestVideo() ?: run { skip("ffmpeg unavailable or test video generation failed"); return }

        runPlayerTest { player ->
            player.setMediaData(UriMediaData(video.absolutePath, emptyMap(), MediaExtraFiles.EMPTY))
            val properties = withTimeout(10_000) {
                player.mediaProperties.first { it?.videoWidth != null && it.videoHeight != null }
            }
            assertEquals(640, properties?.videoWidth)
            assertEquals(360, properties?.videoHeight)
        }
    }

    /**
     * Selecting a non-default SRT subtitle track must survive pause, resume, and seek,
     * and turning subtitles off must stick as well.
     *
     * Regression test for open-ani/animeko#1128: the bundled FFmpeg used to lack all
     * subtitle decoders, so libmpv rejected the selection asynchronously via a
     * "track-list" rewrite; MpvTrackGroup additionally published an optimistic selection
     * that native state then overwrote, which surfaced in the UI as "subtitles reset
     * after pausing".
     */
    @OptIn(InternalMediampApi::class)
    @Test
    fun `subtitle selection persists across pause resume and seek`() {
        if (!prepareOrSkip()) return
        val video = generateDualSubtitleVideo()
            ?: run { skip("ffmpeg unavailable or dual-subtitle video generation failed"); return }

        runPlayerTest { player ->
            // Autoplay through the open itself (spec §3: intent applied natively at open).
            player.setMediaData(
                UriMediaData(video.absolutePath, emptyMap(), MediaExtraFiles.EMPTY),
                playWhenReady = true,
            )
            assertTrue(player.state.value.playWhenReady)
            withTimeout(10_000) { player.state.first { it.isPlaying } }

            val metadata = assertNotNull(player.features[MediaMetadata])
            val subtitles = assertNotNull(metadata.subtitleTracks)
            val candidates = withTimeout(10_000) {
                subtitles.candidates.first { it.size == 2 }
            }
            val second = candidates.first { it.internalId == "2" }
            val handle = player.impl as MPVHandle

            assertTrue(subtitles.select(second))
            // `selected` is confirmed asynchronously from mpv's track-list. It must
            // converge to the requested track (decode success), not flash and revert.
            withTimeout(10_000) {
                subtitles.selected.first { it?.internalId == "2" }
            }
            assertEquals("2", handle.getPropertyString("sid"))

            suspend fun assertStillSelected(what: String) {
                delay(1_000) // time for mpv to emit any track-list rewrites
                assertEquals("2", subtitles.selected.value?.internalId, "selection lost $what")
                assertEquals("2", handle.getPropertyString("sid"), "native sid lost $what")
            }

            player.pause()
            assertFalse(player.state.value.playWhenReady)
            assertStillSelected("after pause")

            player.play()
            withTimeout(10_000) { player.state.first { it.isPlaying } }
            assertStillSelected("after resume")

            player.seekTo(5_000)
            withTimeout(10_000) { player.currentPositionMillis.first { it in 4_500..8_000 } }
            assertStillSelected("after seek")

            // Turning subtitles off must stick through playback controls too.
            assertTrue(subtitles.select(null))
            withTimeout(10_000) {
                subtitles.selected.first { it == null }
            }
            player.pause()
            delay(1_000)
            assertEquals(null, subtitles.selected.value, "subtitles re-enabled after pause")
            assertEquals("no", handle.getPropertyString("sid"))

            player.stopPlayback()
            assertEquals(MediaStatus.Idle, player.state.value.mediaStatus)
        }
    }

    /** Average color of the center 20x20 region of the current frame, read back via [Screenshots]. */
    private suspend fun captureCenterColor(screenshots: Screenshots): Triple<Int, Int, Int> {
        val file = File.createTempFile("mediamp-mpv-shot", ".png")
        try {
            screenshots.takeScreenshot(file.absolutePath)
            val image = assertNotNull(javax.imageio.ImageIO.read(file), "screenshot PNG should be readable")
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            val cx = image.width / 2
            val cy = image.height / 2
            for (x in cx - 10 until cx + 10) {
                for (y in cy - 10 until cy + 10) {
                    val rgb = image.getRGB(x, y)
                    r += (rgb shr 16) and 0xFF; g += (rgb shr 8) and 0xFF; b += rgb and 0xFF; n++
                }
            }
            return Triple((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        } finally {
            file.delete()
        }
    }

    /** Polls screenshots until [predicate] matches — the render thread lags a frame or two behind. */
    private suspend fun awaitCenterColor(
        screenshots: Screenshots,
        what: String,
        predicate: (Triple<Int, Int, Int>) -> Boolean,
    ): Triple<Int, Int, Int> {
        var last: Triple<Int, Int, Int>? = null
        return withTimeout(10_000) {
            while (true) {
                val color = captureCenterColor(screenshots)
                if (predicate(color)) return@withTimeout color
                System.err.println("[SmokeTest] awaiting $what, sampled $color")
                last = color
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable, last=$last")
        }
    }

    /**
     * Pixel-level verification: the real Metal render path must produce frames whose
     * content matches the source video, and seeking must update the rendered frame.
     */
    @OptIn(InternalMediampApi::class, ExperimentalMediampApi::class)
    @Test
    fun `screenshot pixel verification - frame content matches source and updates on seek`() {
        if (!prepareOrSkip()) return
        val video = generateColorVideo() ?: run { skip("ffmpeg unavailable or color video generation failed"); return }

        runPlayerTest { player ->
            player.setMediaData(
                UriMediaData(video.absolutePath, emptyMap(), MediaExtraFiles.EMPTY),
                playWhenReady = true,
            )
            withTimeout(10_000) { player.state.first { it.isPlaying } }
            // Stay well inside the red segment (0.0-2.5s).
            withTimeout(10_000) { player.currentPositionMillis.first { it in 300..1_800 } }
            val screenshots = assertNotNull(player.features[Screenshots.Key])

            // yuv420 + mpeg4 round-trip is lossy; assert dominance, not exact values.
            val red = awaitCenterColor(screenshots, "red") { (r, _, b) -> r > 180 && b < 80 }
            assertTrue(red.first > 180 && red.third < 80, "expected red frame, got $red")

            // Seek into the blue segment; the rendered frame must follow.
            player.seekTo(4_000)
            withTimeout(10_000) { player.currentPositionMillis.first { it in 3_000..5_500 } }
            val blue = awaitCenterColor(screenshots, "blue") { (r, _, b) -> b > 180 && r < 80 }
            assertTrue(blue.third > 180 && blue.first < 80, "expected blue frame after seek, got $blue")

            player.stopPlayback()
            assertEquals(MediaStatus.Idle, player.state.value.mediaStatus)
        }
    }

    @OptIn(InternalMediampApi::class)
    @Test
    fun `seekable input playback via stream_cb`() {
        if (!prepareOrSkip()) return
        val video = generateTestVideo() ?: run { skip("ffmpeg unavailable or test video generation failed"); return }

        runPlayerTest { player ->
            player.setMediaData(FileSeekableInputMediaData(video))
            assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)

            player.play()
            withTimeout(10_000) { player.state.first { it.isPlaying } }
            withTimeout(10_000) { player.currentPositionMillis.first { it > 200 } }

            // Seeking exercises stream_cb seek_fn.
            player.seekTo(2_000)
            withTimeout(10_000) { player.currentPositionMillis.first { it in 1_500..4_500 } }

            player.stopPlayback()
            assertEquals(MediaStatus.Idle, player.state.value.mediaStatus)
        }
    }

    /**
     * Regression test for spurious EOF on still-downloading torrent sources
     * (open-ani/animeko): the await context handed to [SeekableInputMediaData.createInput]
     * used to be the open coroutine's own context, whose job completes right after the
     * open. A read that later blocked for data — TorrentInput does
     * `runBlocking(awaitContext) { piece.awaitFinished() }` on the mpv demux thread — then
     * died instantly with "Parent job is Completed"; the JNI layer cleared the exception
     * and returned -1, which mpv's stream layer treats as EOF: playback "finished" on the
     * first seek into undownloaded data.
     */
    @OptIn(ExperimentalMediampApi::class)
    @Test
    fun `createInput await context outlives the open and is cancelled with the session`() {
        if (!prepareOrSkip()) return
        val video = generateTestVideo() ?: run { skip("ffmpeg unavailable or test video generation failed"); return }

        runPlayerTest { player ->
            var awaitContext: CoroutineContext? = null
            val data = object : SeekableInputMediaData {
                override val uri: String get() = "test://await-context/${video.name}"
                override val extraFiles: MediaExtraFiles get() = MediaExtraFiles.EMPTY
                override val options: List<String> get() = emptyList()
                override fun fileLength(): Long = video.length()
                override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput {
                    awaitContext = coroutineContext
                    return RandomAccessFileSeekableInput(RandomAccessFile(video, "r"))
                }

                override fun close() {}
            }
            player.setMediaData(data)
            assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)

            val context = assertNotNull(awaitContext)
            val awaitJob = assertNotNull(context[Job], "createInput must receive a context carrying a Job")
            assertTrue(awaitJob.isActive, "the await context must stay active after the open completes")

            // The exact shape of a blocked torrent read, on a foreign thread like mpv's
            // demux thread. Must run, not die with "Parent job is Completed".
            var probeError: Throwable? = null
            thread(name = "fake-demux-read") {
                try {
                    runBlocking(context) { delay(10) }
                } catch (t: Throwable) {
                    probeError = t
                }
            }.join()
            assertNull(probeError, "a blocking wait on the await context must work mid-session, got: $probeError")

            // Ending the session must cancel the job so reads still blocked in the await
            // context unwind before the native stream is closed (release is async on the
            // machine's cleanup scope, hence the timeout-join rather than an immediate
            // assertion).
            player.stopPlayback()
            assertEquals(MediaStatus.Idle, player.state.value.mediaStatus)
            withTimeout(5_000) { awaitJob.join() }
            assertTrue(awaitJob.isCancelled, "the await job must be cancelled when the session is released")
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
