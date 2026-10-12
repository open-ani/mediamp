/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface
import org.openani.mediamp.mpv.utils.MpvTestMedia
import org.openani.mediamp.mpv.utils.SkiaLayerRedrawer
import org.openani.mediamp.mpv.utils.WindowsWindowCapture
import org.openani.mediamp.mpv.utils.findSkiaLayer
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * One scenario of the render path matrix: a real Compose window on the Skiko render API this
 * JVM was started with (`skiko.renderApi` plus the scenario's other overrides, set by the
 * `renderPathTest_<scenario>` Gradle tasks, see buildSrc `RenderPathMatrix.kt`), playing a
 * known-colour clip through [MpvMediampPlayerSurface], checked against what the screen
 * actually shows.
 *
 * The headless suites drive the native producer alone. This is the check that frames reach
 * the screen through the consumer path chosen from Skiko's *actual* redrawer, including the
 * silent fallbacks that put user machines without a usable D3D12 device on the OpenGL,
 * ANGLE or software paths. Pixels are what the OS composes for the window (on Windows
 * [WindowsWindowCapture], elsewhere a [Robot] screen capture), so a frame that Skia drew but
 * the window never presented fails too, and so does a see-through or garbage window.
 *
 * A requested API the machine cannot provide is not a failure by default: Skiko falls back
 * as it would for a user, and the scenario reports where it landed. Set
 * `mediamp.mpv.renderpath.strict=true` on a machine expected to support every requested API;
 * strict mode also checks the scenario's expected `hwdec-current`.
 *
 * Each run writes `result.properties` and the captured screens to
 * `mediamp.mpv.renderpath.outputDir`; `renderPathMatrixReport` tabulates them.
 */
class RenderPathMatrixTest {
    private val requestedApi = System.getProperty("skiko.renderApi") ?: "default"
    private val scenario = System.getProperty("mediamp.mpv.renderpath.scenario") ?: requestedApi
    private val outputDir = File(
        System.getProperty("mediamp.mpv.renderpath.outputDir") ?: "build/render-path-matrix/$scenario",
    )
    private val strict = System.getProperty("mediamp.mpv.renderpath.strict") == "true"
    private val hwdecOverride = System.getProperty("mediamp.mpv.renderpath.hwdec")
    private val expectedHwdec = System.getProperty("mediamp.mpv.renderpath.expectedHwdec")

    @OptIn(InternalMediampApi::class)
    @Test
    fun `video reaches the screen through the render path Skiko selects`() {
        if (!MpvTestMedia.prepareOrSkip(TAG)) return
        if (GraphicsEnvironment.isHeadless()) {
            MpvTestMedia.skip(TAG, "AWT is headless")
            return
        }
        val clip = extractClip()
        outputDir.mkdirs()
        val result = Properties().apply {
            setProperty("scenario", scenario)
            setProperty("requestedApi", requestedApi)
            val overrides = listOfNotNull(
                System.getProperty("skiko.gpu.priority")?.let { "skiko.gpu.priority=$it" },
                System.getenv("MEDIAMP_D3D11_ADAPTER")?.let { "MEDIAMP_D3D11_ADAPTER=$it" },
                System.getenv("MEDIAMP_D3D11_MAX_FEATURE_LEVEL")?.let { "MEDIAMP_D3D11_MAX_FEATURE_LEVEL=$it" },
                hwdecOverride?.let { "hwdec=$it" },
                System.getProperty("mediamp.mpv.renderpath.gpuPreference")?.let { "GpuPreference=$it" },
            )
            setProperty("overrides", overrides.joinToString(", "))
        }
        val notableLog = mutableListOf<String>()
        MPVHandle.setLogHandler { message ->
            println("[$TAG][${message.prefix}] ${message.line}")
            if (message.isError || NOTABLE_LOG.any { it in message.line }) {
                synchronized(notableLog) { notableLog += "[${message.prefix}] ${message.line}" }
            }
        }

        runBlocking {
            val player = withContext(Dispatchers.Main) { MpvMediampPlayer(Any(), Dispatchers.Default) }
            val layout = SceneLayout()
            val window = withContext(Dispatchers.Main) {
                ComposeWindow().apply {
                    title = "mediamp render path matrix: $scenario"
                    setContent { RenderPathScene(player, layout) }
                    setBounds(32, 32, 960, 640)
                    isAlwaysOnTop = true
                    isVisible = true
                }
            }
            val screen = ScreenProbe(window, layout)
            try {
                // The surface creates mpv's producer once Skiko has a redrawer; a later Skiko
                // switch can still move it, so the path is recorded again at the end.
                withTimeoutOrNull(30.seconds) {
                    while (player.ringBackend == null) delay(100.milliseconds)
                } ?: throw AssertionError("the surface did not pick an mpv render path within 30s")
                recordPath(window, player, result)
                if (strict) {
                    assertEquals(requestedApi, result.getProperty("actualApi"), "Skiko fell back from the requested API")
                }

                withContext(Dispatchers.Main) {
                    val handle = player.impl as MPVHandle
                    hwdecOverride?.let { handle.setPropertyString("hwdec", it) }
                    player.setMediaData(
                        UriMediaData(clip.absolutePath, emptyMap(), MediaExtraFiles.EMPTY),
                        playWhenReady = false,
                    )
                    // A render-context lifecycle may set hwdec while loading; mpv applies a
                    // runtime change by reinitializing the decoder, before the seeks below.
                    hwdecOverride?.let { handle.setPropertyString("hwdec", it) }
                    player.seekTo(1_000)
                }
                screen.await("segment-a", 30.seconds) { it.video.near(SEGMENT_A_RGB) && it.uiIntact() }

                // A second, different frame proves the path keeps presenting new frames,
                // not just the first one.
                withContext(Dispatchers.Main) { player.seekTo(4_500) }
                screen.await("segment-b", 20.seconds) { it.video.near(SEGMENT_B_RGB) && it.uiIntact() }

                withContext(Dispatchers.Main) { player.play() }
                withTimeoutOrNull(20.seconds) { player.currentPositionMillis.first { it >= 6_500 } }
                    ?: throw AssertionError("playback did not advance past 6.5s; at ${player.currentPositionMillis.value}ms")
                screen.await("playing", 10.seconds) { it.video.near(SEGMENT_B_RGB) && it.uiIntact() }

                withContext(Dispatchers.Main) {
                    val handle = player.impl as MPVHandle
                    result.setProperty("hwdec", handle.getPropertyString("hwdec-current") ?: "unknown")
                    result.setProperty("droppedFrames", handle.getPropertyLongOrNull("frame-drop-count")?.toString() ?: "?")
                }
                recordPath(window, player, result)
                if (strict && expectedHwdec != null) {
                    assertEquals(expectedHwdec, result.getProperty("hwdec"), "hwdec-current")
                }
                result.setProperty("result", "PASS")
            } catch (e: Throwable) {
                result.setProperty("result", "FAIL")
                result.setProperty("failure", e.toString())
                throw e
            } finally {
                val log = synchronized(notableLog) { notableLog.toList() }
                result.setProperty("log", log.joinToString("\n"))
                log.lastMatch(SKIA_ADAPTER)?.let { result.setProperty("skiaAdapter", it) }
                log.lastMatch(MPV_ADAPTER)?.let { result.setProperty("mpvAdapter", it) }
                outputDir.resolve("result.properties").writer().use { result.store(it, null) }
                withContext(Dispatchers.Main) {
                    window.dispose()
                    player.close()
                }
                MPVHandle.setLogHandler(null)
            }
        }
    }

    /** Skiko's API and redrawer and mediamp's render path, as of now. */
    @OptIn(InternalMediampApi::class)
    private suspend fun recordPath(window: ComposeWindow, player: MpvMediampPlayer, result: Properties) {
        withContext(Dispatchers.Main) {
            result.setProperty("actualApi", window.renderApi.name)
            window.findSkiaLayer()?.let { layer ->
                val redrawer = SkiaLayerRedrawer(layer).redrawerOrNull
                result.setProperty("redrawer", redrawer?.javaClass?.simpleName ?: "none")
            }
            player.rendererName?.let { result.setProperty("mediampRenderer", it) }
        }
    }

    /** Where the scene's probe regions are, in root pixels, as last laid out. */
    private class SceneLayout {
        @Volatile
        var root: IntSize = IntSize.Zero

        @Volatile
        var video: Rect = Rect.Zero

        @Volatile
        var overlay: Rect = Rect.Zero
    }

    /**
     * Compose background, the video, and a Compose overlay drawn on top of the video: the
     * three must all show, in that stacking order, for a path to count as working.
     */
    @Composable
    private fun RenderPathScene(player: MpvMediampPlayer, layout: SceneLayout) {
        Box(Modifier.fillMaxSize().background(BACKGROUND).onGloballyPositioned { layout.root = it.size }) {
            Box(
                Modifier.align(Alignment.Center)
                    .fillMaxWidth(0.7f)
                    .aspectRatio(CLIP_WIDTH.toFloat() / CLIP_HEIGHT)
                    .onGloballyPositioned { layout.video = it.boundsInRoot() },
            ) {
                MpvMediampPlayerSurface(player, Modifier.fillMaxSize())
                Box(
                    Modifier.align(Alignment.TopStart)
                        .fillMaxSize(0.25f)
                        .background(OVERLAY)
                        .onGloballyPositioned { layout.overlay = it.boundsInRoot() },
                )
            }
        }
    }

    /** Average colours of the probe regions in one screen capture. */
    private class Samples(val background: Int, val video: Int, val overlay: Int) {
        fun uiIntact(): Boolean = background.near(BACKGROUND.rgb) && overlay.near(OVERLAY.rgb)

        override fun toString(): String =
            "background=${background.hex()} video=${video.hex()} overlay=${overlay.hex()}"
    }

    private inner class ScreenProbe(private val window: ComposeWindow, private val layout: SceneLayout) {
        private val robot = if (hostOs == OS.Windows) null else Robot()

        /**
         * Captures until [accept] holds or [timeout] passes; the last capture is saved as
         * `<name>.png` either way, so a failing cell shows what was on screen.
         */
        suspend fun await(name: String, timeout: Duration, accept: (Samples) -> Boolean): Samples {
            val deadline = TimeSource.Monotonic.markNow() + timeout
            while (true) {
                val (image, samples) = capture()
                val accepted = samples != null && accept(samples)
                if (accepted || deadline.hasPassedNow()) {
                    image?.let { ImageIO.write(it, "png", outputDir.resolve("$name.png")) }
                    if (accepted) return samples!!
                    throw AssertionError("$name: screen did not match within $timeout; last capture: ${samples ?: "none"}")
                }
                delay(250.milliseconds)
            }
        }

        private suspend fun capture(): Pair<BufferedImage?, Samples?> {
            val root = layout.root
            if (root.width == 0 || root.height == 0 || layout.video.isEmpty) return null to null
            val image = if (robot == null) {
                withContext(Dispatchers.Main) {
                    window.findSkiaLayer()?.takeIf { it.isShowing }?.let(WindowsWindowCapture::capture)
                }
            } else {
                val bounds = withContext(Dispatchers.Main) {
                    window.findSkiaLayer()?.takeIf { it.isShowing }?.let { Rectangle(it.locationOnScreen, it.size) }
                }
                bounds?.let { withContext(Dispatchers.IO) { robot.createScreenCapture(it) } }
            } ?: return null to null
            // Layout is in root pixels, the capture in screen pixels: sample by fraction.
            fun at(point: Offset): Int = image.averageAt(point.x / root.width, point.y / root.height)
            val video = layout.video
            return image to Samples(
                background = at(Offset(video.left / 2, video.top / 2)),
                video = at(Offset(video.left + video.width * 0.75f, video.top + video.height * 0.75f)),
                overlay = at(layout.overlay.center),
            )
        }
    }

    /**
     * `render-path-clip.mp4`: two solid segments, [SEGMENT_A_RGB] for 0-3s then
     * [SEGMENT_B_RGB] for 3-9s, 640x360 H.264 so GPUs can decode it in hardware (d3d11va,
     * videotoolbox) the way real content is played. Keyframes every 0.5s let a paused seek
     * land on the right colour quickly. Generated with:
     *
     * ```
     * ffmpeg -f lavfi -i color=c=0xE06000:size=640x360:rate=30:duration=3 \
     *   -f lavfi -i color=c=0x0060E0:size=640x360:rate=30:duration=6 \
     *   -filter_complex "[0:v][1:v]concat=n=2:v=1:a=0,format=yuv420p[v]" -map "[v]" \
     *   -c:v libx264 -preset veryslow -crf 18 -g 15 -bf 0 -movflags +faststart \
     *   -map_metadata -1 -fflags +bitexact render-path-clip.mp4
     * ```
     */
    private fun extractClip(): File {
        val target = File.createTempFile("mediamp-mpv-render-path-clip", ".mp4").apply { deleteOnExit() }
        val resource = checkNotNull(javaClass.getResourceAsStream("/render-path-clip.mp4")) {
            "render-path-clip.mp4 is missing from the test resources"
        }
        resource.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private companion object {
        const val TAG = "RenderPathMatrix"
        const val CLIP_WIDTH = 640
        const val CLIP_HEIGHT = 360

        // Distinct from each other and from black and white, so a black, white, stale or
        // see-through region never passes for the expected one.
        const val SEGMENT_A_RGB = 0xE06000
        const val SEGMENT_B_RGB = 0x0060E0
        val BACKGROUND = Color(0xFF7A1FA2)
        val OVERLAY = Color(0xFF16A34A)

        /** Per-channel slack for YUV round trips, scaling and colour management. */
        const val TOLERANCE = 48

        /** Log lines worth keeping in the matrix report besides errors. */
        val NOTABLE_LOG = listOf("adapter", " surface", "Fallback", "hwdec", "readback", "overrides", "D3D11CreateDevice")

        /** The native producer's adapter log lines (render_d3d11.cpp), including the mismatch error. */
        val SKIA_ADAPTER = Regex("""Skia(?:'s)? D3D12 device (?:is )?on adapter '([^']*)'""")
        val MPV_ADAPTER = Regex("""D3D11 device on adapter '([^']*)'""")

        fun List<String>.lastMatch(pattern: Regex): String? =
            asReversed().firstNotNullOfOrNull { pattern.find(it)?.groupValues?.get(1) }

        val Color.rgb: Int get() = toArgb() and 0xFFFFFF

        fun Int.hex(): String = "#%06X".format(this and 0xFFFFFF)

        fun Int.near(expected: Int): Boolean = (0..16 step 8).all { shift ->
            kotlin.math.abs((this shr shift and 0xFF) - (expected shr shift and 0xFF)) <= TOLERANCE
        }

        /** Average RGB of a small square around the fractional position ([fx], [fy]). */
        fun BufferedImage.averageAt(fx: Float, fy: Float, radius: Int = 4): Int {
            val cx = (fx * width).toInt().coerceIn(radius, width - radius - 1)
            val cy = (fy * height).toInt().coerceIn(radius, height - radius - 1)
            var r = 0
            var g = 0
            var b = 0
            var n = 0
            for (y in cy - radius..cy + radius) {
                for (x in cx - radius..cx + radius) {
                    val pixel = getRGB(x, y)
                    r += pixel shr 16 and 0xFF
                    g += pixel shr 8 and 0xFF
                    b += pixel and 0xFF
                    n++
                }
            }
            return (r / n shl 16) or (g / n shl 8) or (b / n)
        }
    }
}
