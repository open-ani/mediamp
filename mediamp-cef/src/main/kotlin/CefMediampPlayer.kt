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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.browser.CefRendering
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Desktop‑JVM backend built on the **raw JCEF** bundled with JetBrains Runtime 23.
 *
 * It embeds an HTML5 `<video>` element inside a [CefBrowser] and translates DOM
 * callbacks to [MediampPlayer] state through a `CefMessageRouter` bridge.
 */
public class CefMediampPlayer(
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    private val cefApp: CefApp = CefApp.getInstance(),
) : AbstractMediampPlayer<CefMediampPlayer.BrowserData>(parentCoroutineContext) {

    // region Mutable state ----------------------------------------------------------------------
    private val _mediaProperties = MutableStateFlow<MediaProperties?>(null)
    override val mediaProperties: StateFlow<MediaProperties?> get() = _mediaProperties

    private val _currentPositionMillis = MutableStateFlow(0L)
    override val currentPositionMillis: StateFlow<Long> get() = _currentPositionMillis

    // endregion

    // region Features ---------------------------------------------------------------------------
    private val bufferingFeature = CefBuffering()
    private val playbackSpeedFeature = CefPlaybackSpeed()
    private val mediaMetadataFeature = CefMediaMetadata()

    override val features: PlayerFeatures = buildPlayerFeatures {
        add(Buffering, bufferingFeature)
        add(PlaybackSpeed, playbackSpeedFeature)
        add(MediaMetadata, mediaMetadataFeature)
    }
    // endregion

    // The underlying CEF entities (created lazily in [setDataImpl])
    private var cefClient: CefClient? = null
    private var cefBrowser: CefBrowser? by mutableStateOf(null)
    private var messageRouter: CefMessageRouter? = null

    override val impl: Any get() = cefBrowser ?: Unit

    // --------------------------------------------------------------------------------------------
    public class BrowserData(
        mediaData: MediaData,
        public val browser: CefBrowser,
        public val loadMedia: suspend () -> Unit,
        public val dispose: () -> Unit,
    ) : Data(mediaData, dispose)

    // --------------------------------------------------------------------------------------------
    // Media‑source resolver ----------------------------------------------------------------------
    override suspend fun setDataImpl(data: MediaData): BrowserData = when (data) {
        is UriMediaData -> suspendCancellableCoroutine { cont ->
            SwingUtilities.invokeLater {
                cont.resumeWith(runCatching { createBrowserData(data) })
            }
        }

        else -> throw UnsupportedOperationException("Unsupported MediaData type: ${data::class.simpleName}")
    }

    private fun createBrowserData(data: UriMediaData): BrowserData {
        val app = cefApp // already initialised by JetBrains Runtime
        val client = app.createClient()

        // Message router                      
        val routerConfig = CefMessageRouter.CefMessageRouterConfig()
        val router = CefMessageRouter.create(routerConfig)
        val handler = BridgeHandler()
        router.addHandler(handler, true)
        client.addMessageRouter(router)

        val browser = client.createBrowser(
            "about:blank",
            CefRendering.DEFAULT,
            true,
        )

        return BrowserData(
            mediaData = data,
            browser = browser,
            loadMedia = {
                // Build HTML and load through data‑url
                val html = buildPlayerHtml(data.uri, data.extraFiles.subtitles)
                val encoded = URLEncoder.encode(html, StandardCharsets.UTF_8)
                browser.loadURL("data:text/html;charset=utf-8,$encoded")
            },
            dispose = {
                router.removeHandler(handler)
                router.dispose()
                browser.close(true)
                client.dispose()
                data.close()
            },
        ).also {
            // Keep references for control methods
            cefClient = client
            cefBrowser = browser
            messageRouter = router
        }
    }

    // --------------------------------------------------------------------------------------------
    // HTML generator & JS bridge ----------------------------------------------------------------
    private fun buildPlayerHtml(mediaUri: String, subtitles: List<Subtitle>): String = buildString {
        fun appendHtml(@Language("html") s: String) {
            append(s)
        }
        appendHtml("""<!DOCTYPE html><html><head><meta charset='utf-8'>""")
        appendHtml(
            """
            <style>
              html,body{margin:0;background:#000;height:100%;overflow:hidden;}
              video{width:100%;height:100%;outline:none;}
            </style>
            </head><body>
            <h1>Test video</h1>
            <video id='player' controls autoplay crossorigin='anonymous'>
               <source src='$mediaUri'>
            """,
        )
        subtitles.forEach {
            appendHtml("<track kind='subtitles' src='${it.uri}'")
            it.label?.let { l -> appendHtml(" label='${l}'") }
            it.language?.let { lang -> appendHtml(" srclang='${lang}'") }
            appendHtml(" />")
        }
        appendHtml("</video>")

        appendHtml(
            """
            <script>
              const player = document.getElementById('player');
              const send = (t,v)=> window.cefQuery({request:`${'$'}{t}:${'$'}{v}`});

              // position / buffered
              player.addEventListener('timeupdate', ()=> send('pos', Math.floor(player.currentTime*1000)));
              player.addEventListener('progress', ()=> {
                 if(player.buffered.length>0 && player.duration>0){
                   const perc = Math.floor(player.buffered.end(player.buffered.length-1)/player.duration*100);
                   send('buf', perc);
                 }
              });

              // state
              player.addEventListener('waiting', ()=> send('state','buffering'));
              player.addEventListener('playing', ()=> send('state','playing'));
              player.addEventListener('pause',   ()=> send('state','paused'));
              player.addEventListener('ended',   ()=> send('state','ended'));

              // metadata
              player.addEventListener('loadedmetadata', ()=> send('dur', Math.floor(player.duration*1000)));

              // rate
              player.addEventListener('ratechange', ()=> send('rate', player.playbackRate));
            </script></body></html>
            """,
        )
    }

    /**
     * Receives messages from the JS bridge via `window.cefQuery`.
     * Supported messages (all `String`):
     * * `pos:<millis>`          – current position
     * * `dur:<millis>`          – total duration
     * * `buf:<percent>`         – buffered percentage 0‑100
     * * `state:<playing|paused|buffering|ended>`
     * * `rate:<float>`          – playback rate
     */
    private inner class BridgeHandler : CefMessageRouterHandlerAdapter() {
        override fun onQuery(
            browser: CefBrowser?,
            frame: CefFrame?,
            queryId: Long,
            request: String?,
            persistent: Boolean,
            callback: CefQueryCallback?
        ): Boolean {
            println("CefMediampPlayer.onQuery: $request")
            try {
                handleMessage(request ?: "")
                callback?.success("OK")
            } catch (e: Exception) {
                callback?.failure(0, e.message)
            }
            return true // handled
        }

        private fun handleMessage(msg: String) {
            val idx = msg.indexOf(":")
            if (idx == -1) return
            val type = msg.substring(0, idx)
            val payload = msg.substring(idx + 1)
            when (type) {
                "pos" -> _currentPositionMillis.value = payload.toLongOrNull() ?: 0L
                "dur" -> {
                    val dur = payload.toLongOrNull() ?: 0L
                    val title =
                        openResource.value?.mediaData?.let { (it as? UriMediaData)?.uri?.substringAfterLast('/') }
                    _mediaProperties.value = MediaProperties(title = title, durationMillis = dur)
                }

                "buf" -> bufferingFeature.bufferedPercentage.value = payload.toIntOrNull() ?: 0
                "rate" -> playbackSpeedFeature.valueFlow.value = payload.toFloatOrNull() ?: 1f
                "state" -> when (payload) {
                    "playing" -> {
                        playbackState.value = PlaybackState.PLAYING
                        bufferingFeature.isBuffering.value = false
                    }

                    "paused" -> playbackState.value = PlaybackState.PAUSED
                    "buffering" -> {
                        playbackState.value = PlaybackState.PAUSED_BUFFERING
                        bufferingFeature.isBuffering.value = true
                    }

                    "ended" -> playbackState.value = PlaybackState.FINISHED
                }
            }
        }
    }

    // --------------------------------------------------------------------------------------------
    // Start/stop/close ---------------------------------------------------------------------------
    override suspend fun startPlayer(data: BrowserData) {
        withContext(Dispatchers.Main.immediate) {
            data.loadMedia()
        }
    }

    override fun stopPlaybackImpl() {
        execJS("const p=document.getElementById('player'); if(p){p.pause();p.removeAttribute('src');p.load();}")
        _currentPositionMillis.value = 0L
        playbackState.value = PlaybackState.PAUSED
    }

    override fun closeImpl() {
        openResource.value?.releaseResource?.invoke()
        cefBrowser = null
        cefClient = null
        messageRouter = null
    }

    // --------------------------------------------------------------------------------------------
    // Player commands ----------------------------------------------------------------------------
    private fun execJS(code: String) {
        cefBrowser?.executeJavaScript(code, cefBrowser?.url ?: "about:blank", 0)
    }

    override fun resume() {
        execJS("document.getElementById('player')?.play();")
        playbackState.value = PlaybackState.PLAYING
    }

    override fun pause() {
        execJS("document.getElementById('player')?.pause();")
        playbackState.value = PlaybackState.PAUSED
    }

    override fun seekTo(positionMillis: Long) {
        execJS("const p=document.getElementById('player'); if(p){p.currentTime=${'$'}{positionMillis.toDouble()/1000};}")
        _currentPositionMillis.value = positionMillis
    }

    // --------------------------------------------------------------------------------------------
    // Sync helpers -------------------------------------------------------------------------------
    override fun getCurrentMediaProperties(): MediaProperties? = _mediaProperties.value
    override fun getCurrentPlaybackState(): PlaybackState = playbackState.value
    override fun getCurrentPositionMillis(): Long = _currentPositionMillis.value

    // --------------------------------------------------------------------------------------------
    // Feature impls ------------------------------------------------------------------------------
    private class CefBuffering : Buffering {
        override val isBuffering: MutableStateFlow<Boolean> = MutableStateFlow(false)
        override val bufferedPercentage: MutableStateFlow<Int> = MutableStateFlow(0)
    }

    private inner class CefPlaybackSpeed : PlaybackSpeed {
        override val valueFlow: MutableStateFlow<Float> = MutableStateFlow(1f)
        override val value: Float get() = valueFlow.value
        override fun set(speed: Float) {
            val s = speed.coerceAtLeast(0f)
            valueFlow.value = s
            execJS("document.getElementById('player').playbackRate=${'$'}s;")
        }
    }

    private class CefMediaMetadata : MediaMetadata {
        override val subtitleTracks: MutableTrackGroup<SubtitleTrack> = MutableTrackGroup()
        override val audioTracks: MutableTrackGroup<AudioTrack> = MutableTrackGroup()
        override val chapters: StateFlow<List<Chapter>> = MutableStateFlow(emptyList())
    }
}
