/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(InternalMediampApi::class, ExperimentalMediampApi::class, InternalForInheritanceMediampApi::class)

package org.openani.mediamp.cef

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.intellij.lang.annotations.Language
import org.openani.mediamp.AbstractMediampPlayer
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.buildPlayerFeatures
import org.openani.mediamp.internal.MutableTrackGroup
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.Chapter
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.Subtitle
import org.openani.mediamp.source.UriMediaData
import java.io.File
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.suspendCoroutine

/**
 * Desktop-JVM backend built on the **raw JCEF** bundled with JetBrains Runtime 23.
 *
 * It embeds an HTML5 `<video>` element inside a [CefBrowser] and translates DOM callbacks to
 * [MediampPlayer] state through a `CefMessageRouter` bridge.
 */
public class CefMediampPlayer(
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    private val cefApp: CefApp = CefApp.getInstance(),
) : AbstractMediampPlayer<CefMediampPlayer.BrowserData>(parentCoroutineContext) {

    // ────────────────────── State ────────────────────────────────────────────────────────────────
    private val _mediaProperties = MutableStateFlow<MediaProperties?>(null)
    override val mediaProperties: StateFlow<MediaProperties?> get() = _mediaProperties

    private val _currentPositionMillis = MutableStateFlow(0L)
    override val currentPositionMillis: StateFlow<Long> get() = _currentPositionMillis

    // ────────────────────── Features ────────────────────────────────────────────────────────────
    private val bufferingFeature = CefBuffering()
    private val playbackSpeedFeature = CefPlaybackSpeed()
    private val mediaMetadataFeature = CefMediaMetadata()

    override val features: PlayerFeatures = buildPlayerFeatures {
        add(Buffering, bufferingFeature)
        add(PlaybackSpeed, playbackSpeedFeature)
        add(MediaMetadata, mediaMetadataFeature)
    }

    // ────────────────────── CEF objects ─────────────────────────────────────────────────────────
    private var cefClient: CefClient? = null
    private var cefBrowser: CefBrowser? by mutableStateOf(null)
    private var messageRouter: CefMessageRouter? = null

    override val impl: Any get() = cefBrowser ?: Unit

    // ────────────────────── Data holder ─────────────────────────────────────────────────────────
    public class BrowserData(
        mediaData: MediaData,
        public val browser: CefBrowser,
        public val loadMedia: suspend () -> Unit,
        dispose: () -> Unit,
    ) : Data(mediaData, dispose)

    // ────────────────────── Media-source resolver ───────────────────────────────────────────────
    override suspend fun setDataImpl(data: MediaData): BrowserData = when (data) {
        is UriMediaData -> createBrowserDataOnEdt(data)
        else -> throw UnsupportedOperationException("Unsupported MediaData: ${data::class.simpleName}")
    }

    private suspend fun createBrowserDataOnEdt(data: UriMediaData): BrowserData = suspendCoroutine { cont ->
        SwingUtilities.invokeLater {
            cont.resumeWith(runCatching { createBrowserData(data) })
        }
    }

    private fun createBrowserData(data: UriMediaData): BrowserData {
        val client = cefApp.createClient()
        val router = CefMessageRouter.create(CefMessageRouter.CefMessageRouterConfig())
        val handler = BridgeHandler()
        router.addHandler(handler, true)
        client.addMessageRouter(router)

        val html = buildPlayerHtml(data.uri, data.extraFiles.subtitles)
        val temp = File.createTempFile("mediamp-player", ".html").apply {
            writeText(html)
            deleteOnExit()
        }
        val browser = client.createBrowser(temp.toURI().toString(), false, false)

        return BrowserData(
            mediaData = data,
            browser = browser,
            loadMedia = {
                // Must be run on EDT
            },
            dispose = {
                router.removeHandler(handler)
                router.dispose()
                browser.close(true)
                client.dispose()
                data.close()
            },
        ).also {
            cefClient = client
            cefBrowser = browser
            messageRouter = router
        }
    }

    // ────────────────────── HTML generator ─────────────────────────────────────────────────────
    private fun buildPlayerHtml(mediaUri: String, subtitles: List<Subtitle>): String = buildString {
        fun h(@Language("html") s: String) = append(s)

        val typeAttr = if (mediaUri.endsWith(".m3u8")) {
//            "type='application/x-mpegURL'"
            ""
        } else {
            ""
        }

        h(
            """
<!DOCTYPE html>
<html>
<link href="https://vjs.zencdn.net/7.2.3/video-js.css" rel="stylesheet">

<head>
    <meta charset='utf-8'>
    <style>html, body {
        margin: 0;
        background: #000;
        color: #fff;
        height: 100%;
        overflow: hidden;
    }

    video {
        width: 100vw;
        height: 100vh;
        outline: none;
    }</style>
</head>
<body>
<h1>$mediaUri</h1>
<video id='player' autoplay>
    <source src='$mediaUri' $typeAttr>
</video>

<script src="https://vjs.zencdn.net/7.2.3/video.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/videojs-contrib-hls/5.14.1/videojs-contrib-hls.js"></script>

<script>
    var player = videojs('player');
    player.play();
</script>

<script>
    const p = document.getElementById('player');
    const send = (t, v) => window.cefQuery({request: `${'$'}{t}:${'$'}{v}`});
    p.addEventListener('timeupdate', () => send('pos', Math.floor(p.currentTime * 1000)));
    p.addEventListener('progress', () => {
        if (p.buffered.length > 0 && p.duration > 0) {
            send('buf', Math.floor(p.buffered.end(p.buffered.length - 1) / p.duration * 100));
        }
    });
    p.addEventListener('waiting', () => send('state', 'buffering'));
    p.addEventListener('playing', () => send('state', 'playing'));
    p.addEventListener('pause', () => send('state', 'paused'));
    p.addEventListener('ended', () => send('state', 'ended'));
    p.addEventListener('loadedmetadata', () => {
        send('dur', Math.floor(p.duration * 1000));
        if (p.autoplay) {
            p.play().catch(() => {
            });
        }
    });
    p.addEventListener('ratechange', () => send('rate', p.playbackRate));
</script>
</body>
</html>
        """.trimIndent(),
        )
    }

    // ────────────────────── Message bridge ────────────────────────────────────────────────────
    private inner class BridgeHandler : CefMessageRouterHandlerAdapter() {
        override fun onQuery(
            browser: CefBrowser?,
            frame: CefFrame?,
            queryId: Long,
            request: String?,
            persistent: Boolean,
            callback: CefQueryCallback?
        ): Boolean {
            try {
                print("CefMediampPlayer: $request")
                handle(request ?: "")
                callback?.success("OK")
            } catch (e: Exception) {
                callback?.failure(0, e.message)
            }
            return true
        }

        private fun handle(msg: String) {
            val sep = msg.indexOf(":")
            if (sep == -1) return
            val type = msg.substring(0, sep)
            val payload = msg.substring(sep + 1)
            when (type) {
                "pos" -> _currentPositionMillis.value = payload.toLongOrNull() ?: 0L
                "dur" -> {
                    val dur = payload.toLongOrNull() ?: 0L
                    val title = (openResource.value?.mediaData as? UriMediaData)?.uri?.substringAfterLast('/')
                    _mediaProperties.value = MediaProperties(title = title, durationMillis = dur)
                }

                "buf" -> bufferingFeature.bufferedPercentage.value = payload.toIntOrNull() ?: 0
                "rate" -> playbackSpeedFeature.valueFlow.value = payload.toFloatOrNull() ?: 1f
                "state" -> when (payload) {
                    "playing" -> {
                        playbackState.value = PlaybackState.PLAYING; bufferingFeature.isBuffering.value = false
                    }

                    "paused" -> playbackState.value = PlaybackState.PAUSED
                    "buffering" -> {
                        playbackState.value = PlaybackState.PAUSED_BUFFERING; bufferingFeature.isBuffering.value = true
                    }

                    "ended" -> playbackState.value = PlaybackState.FINISHED
                }
            }
        }
    }

    // ────────────────────── Lifecycle ─────────────────────────────────────────────────────────
    override suspend fun startPlayer(data: BrowserData) {
        withContext(Dispatchers.Main.immediate) { data.loadMedia() }
    }

    override fun stopPlaybackImpl() {
        execJS("const v=document.getElementById('player'); if(v){v.pause();v.removeAttribute('src');v.load();}")
        _currentPositionMillis.value = 0L
        playbackState.value = PlaybackState.PAUSED
    }

    override fun closeImpl() {
        openResource.value?.releaseResource?.invoke()
        cefBrowser = null
        cefClient = null
        messageRouter = null
    }

    // ────────────────────── Player commands ───────────────────────────────────────────────────
    private fun execJS(code: String) = cefBrowser?.executeJavaScript(code, cefBrowser?.url ?: "about:blank", 0)
    override fun resume() {
        execJS("document.getElementById('player')?.play();"); playbackState.value = PlaybackState.PLAYING
    }

    override fun pause() {
        execJS("document.getElementById('player')?.pause();"); playbackState.value = PlaybackState.PAUSED
    }

    override fun seekTo(positionMillis: Long) {
        execJS("const p=document.getElementById('player'); if(p){p.currentTime=${'$'}{positionMillis/1000.0};}"); _currentPositionMillis.value =
            positionMillis
    }

    // ────────────────────── Accessors ─────────────────────────────────────────────────────────
    override fun getCurrentMediaProperties(): MediaProperties? = _mediaProperties.value
    override fun getCurrentPlaybackState(): PlaybackState = playbackState.value
    override fun getCurrentPositionMillis(): Long = _currentPositionMillis.value

    // ────────────────────── Feature impls ─────────────────────────────────────────────────────
    private class CefBuffering : Buffering {
        override val isBuffering = MutableStateFlow(false);
        override val bufferedPercentage = MutableStateFlow(0)
    }

    private inner class CefPlaybackSpeed : PlaybackSpeed {
        override val valueFlow = MutableStateFlow(1f);
        override val value get() = valueFlow.value;
        override fun set(speed: Float) {
            val s = speed.coerceAtLeast(0f); valueFlow.value =
                s; execJS("document.getElementById('player').playbackRate=${'$'}s;")
        }
    }

    private class CefMediaMetadata : MediaMetadata {
        override val subtitleTracks = MutableTrackGroup<SubtitleTrack>();
        override val audioTracks = MutableTrackGroup<AudioTrack>();
        override val chapters: StateFlow<List<Chapter>> = MutableStateFlow(emptyList())
    }
}
