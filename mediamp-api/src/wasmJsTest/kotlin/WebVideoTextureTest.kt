@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.webgl.WebGLRenderTarget
import androidx.compose.ui.platform.webgl.rememberWebGLRenderTarget
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.khronos.webgl.WebGLRenderingContext
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.observeVideoFrames
import org.openani.mediamp.compose.observeVideoSubtitles
import org.openani.mediamp.compose.stopObservingVideoFrames
import org.openani.mediamp.compose.uploadVideoFrame
import org.openani.mediamp.source.UriMediaData
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val TEST_VIDEO_URL = "/test-resources/test-video.mp4"

class WebVideoTextureTest {
    @Test
    fun videoTextureIsUprightClippedAndBelowComposeOverlays() = runTest(timeout = 60.seconds) {
        withContext(Dispatchers.Main) {
            val restoreContext = preserveDrawingBuffer()
            val container = document.createElement("div") as HTMLElement
            container.style.width = "64px"
            container.style.height = "64px"
            document.body!!.appendChild(container)
            val player = WebMediampPlayer()
            var probe: WebGLRenderTarget? = null
            try {
                assertEquals("anonymous", player.videoElement.crossOrigin)
                ComposeViewport(container) {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        probe = rememberWebGLRenderTarget(IntSize(1, 1))
                        Box(Modifier.fillMaxSize().background(Color.Green)) {
                            Box(Modifier.size(32.dp)) {
                                MediampPlayerSurface(player, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
                                Box(Modifier.align(Alignment.TopCenter).size(4.dp).background(Color.Yellow))
                            }
                        }
                    }
                }
                player.setMediaData(UriMediaData(TEST_VIDEO_URL))
                waitUntil { probe != null && player.videoElement.readyState >= 2 }
                val target = assertNotNull(probe)
                val gl = target.webGLContext
                waitUntil { pixel(gl, target.htmlCanvas.height, 16, 8).isRed() }
                assertTrue(pixel(gl, target.htmlCanvas.height, 16, 24).isBlue(), "video was flipped vertically")
                assertTrue(pixel(gl, target.htmlCanvas.height, 1, 1).isGreen(), "rounded clipping was ignored")
                assertTrue(pixel(gl, target.htmlCanvas.height, 16, 2).isYellow(), "video covered the Compose overlay")
                assertTrue(pixel(gl, target.htmlCanvas.height, 48, 24).isGreen(), "video leaked outside its bounds")
                assertEquals(0, gl.getError(), "texture upload left a WebGL error")

                player.stopPlayback()
                waitUntil { pixel(gl, target.htmlCanvas.height, 16, 8).isGreen() }
            } finally {
                player.close()
                document.body!!.removeChild(container)
                restoreDrawingBuffer(restoreContext)
                delay(20) // Wait for viewport disposal.
            }
        }
    }

    @Test
    fun textureUploadErrorsAreNotSuppressed() {
        val gl = failingUploadContext()
        val texture = assertNotNull(gl.createTexture())
        val video = document.createElement("video") as HTMLVideoElement
        try {
            val error = assertFailsWith<Throwable> { uploadVideoFrame(gl, texture, video) }
            assertTrue(error.message?.contains("texture upload denied") == true)
        } finally {
            gl.deleteTexture(texture)
        }
    }

    @Test
    fun explicitCorsCredentialPolicyIsNotOverwritten() {
        val video = document.createElement("video") as HTMLVideoElement
        video.crossOrigin = "use-credentials"
        val player = WebMediampPlayer(video)
        try {
            assertEquals("use-credentials", video.crossOrigin)
        } finally {
            player.close()
        }
    }

    @Test
    fun frameObserverStopsAfterDisposal() {
        val video = document.createElement("video") as HTMLVideoElement
        var frames = 0
        val stop = observeVideoFrames(video) { _, _ -> frames++ }
        val initial = frames
        video.dispatchEvent(Event("resize"))
        assertEquals(initial + 1, frames)
        stopObservingVideoFrames(stop)
        video.dispatchEvent(Event("resize"))
        video.dispatchEvent(Event("emptied"))
        assertEquals(initial + 1, frames)
    }

    @Test
    fun subtitlesFollowNativeCueTimingAndDetach() = runTest(timeout = 60.seconds) {
        withContext(Dispatchers.Main) {
            val player = WebMediampPlayer()
            var stop: JsAny? = null
            try {
                player.setMediaData(UriMediaData(TEST_VIDEO_URL))
                waitUntil { player.videoElement.readyState >= 2 }
                addSubtitleCue(player.videoElement)
                var text = ""
                stop = observeVideoSubtitles(player.videoElement) { text = it }
                player.videoElement.muted = true
                player.play()
                waitUntil { text == "Caption" }
                stopObservingVideoFrames(stop)
                stop = null
                player.stopPlayback()
                delay(20)
                assertEquals("Caption", text, "disposed subtitle observer received another event")
            } finally {
                stop?.let(::stopObservingVideoFrames)
                player.close()
            }
        }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        repeat(500) {
            if (predicate()) return
            delay(20)
        }
        error("Timed out waiting for the video frame")
    }
}

private fun Int.isRed(): Boolean = (ushr(24) and 255) > 200 && (ushr(8) and 255) < 50
private fun Int.isBlue(): Boolean = (ushr(8) and 255) > 200 && (ushr(24) and 255) < 50
private fun Int.isGreen(): Boolean = (ushr(16) and 255) > 200 && (ushr(24) and 255) < 50
private fun Int.isYellow(): Boolean = (ushr(24) and 255) > 200 && (ushr(16) and 255) > 200

@JsFun("""() => {
    const gl = document.createElement('canvas').getContext('webgl2');
    if (!gl) throw new Error('WebGL2 is required for the upload test');
    gl.texSubImage2D = () => { throw new DOMException('texture upload denied', 'SecurityError'); };
    return gl;
}""")
private external fun failingUploadContext(): WebGLRenderingContext

@JsFun("""(video) => {
    const track = video.addTextTrack('subtitles', 'English', 'en');
    track.mode = 'showing';
    track.addCue(new VTTCue(0, 1, '<b>Caption</b>'));
}""")
private external fun addSubtitleCue(video: HTMLVideoElement)

@JsFun("""(gl, height, x, y) => {
    const framebuffer = gl.getParameter(gl.FRAMEBUFFER_BINDING);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    const pixel = new Uint8Array(4);
    gl.readPixels(x, height - y - 1, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
    gl.bindFramebuffer(gl.FRAMEBUFFER, framebuffer);
    return (pixel[0] << 24) | (pixel[1] << 16) | (pixel[2] << 8) | pixel[3];
}""")
private external fun pixel(gl: WebGLRenderingContext, height: Int, x: Int, y: Int): Int

@JsFun("""() => {
    const original = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function(type, attributes) {
        if (type === 'webgl2') attributes = Object.assign({}, attributes, {preserveDrawingBuffer: true});
        return original.call(this, type, attributes);
    };
    return original;
}""")
private external fun preserveDrawingBuffer(): JsAny

@JsFun("(original) => { HTMLCanvasElement.prototype.getContext = original; }")
private external fun restoreDrawingBuffer(original: JsAny)
