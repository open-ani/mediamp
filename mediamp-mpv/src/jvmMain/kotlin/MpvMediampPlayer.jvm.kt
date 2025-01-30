/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.flow.MutableStateFlow
import org.openani.mediamp.AbstractMediampPlayer
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.buildPlayerFeatures
import org.openani.mediamp.internal.Platform
import org.openani.mediamp.internal.currentPlatform
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import kotlin.coroutines.CoroutineContext

@kotlin.OptIn(InternalMediampApi::class)
actual class MpvMediampPlayer (
    context: Any,
    parentCoroutineContext: CoroutineContext,
) : AbstractMediampPlayer<MpvMediampPlayer.MPVPlayerData>(parentCoroutineContext) {
    class MPVPlayerData(
        mediaData: MediaData,
        releaseResource: () -> Unit,
    ) : Data(mediaData, releaseResource)
    
    private val handle = MPVHandle(context)
    
    private val eventListener = object : EventListener {
        override fun onPropertyChange(name: String) {
            
        }

        override fun onPropertyChange(name: String, value: Boolean) {
            when (name) {
                "pause" -> playbackState.value = 
                    if (value) PlaybackState.PAUSED else PlaybackState.PLAYING
                "paused-for-cache" -> playbackState.value =
                    if (value) PlaybackState.PAUSED_BUFFERING else PlaybackState.PLAYING
                
            }
        }

        override fun onPropertyChange(name: String, value: Long) {
            when (name) {
                "time-pos/full" -> currentPositionMillis.value = value * 1000
                "duration/full" -> mediaProperties.value =
                    if (mediaProperties.value == null) MediaProperties(null, value * 1000)
                    else mediaProperties.value?.copy(durationMillis = value * 1000)
            }
        }

        override fun onPropertyChange(name: String, value: Double) {
        }

        override fun onPropertyChange(name: String, value: String) {
            when (name) {
                "media-title" -> mediaProperties.value =
                    if (mediaProperties.value == null) MediaProperties(value, -1)
                    else mediaProperties.value?.copy(title = value)
            }
        }

    }

    override val impl: MPVHandle get() = handle

    override val currentPositionMillis: MutableStateFlow<Long> = MutableStateFlow(0L)
    
    override val mediaProperties: MutableStateFlow<MediaProperties?> = MutableStateFlow(null)

    override val features: PlayerFeatures = buildPlayerFeatures { }
    
    override fun getCurrentMediaProperties(): MediaProperties? {
        return mediaProperties.value
    }

    override fun getCurrentPlaybackState(): PlaybackState {
        return playbackState.value
    }

    override fun getCurrentPositionMillis(): Long {
        return currentPositionMillis.value
    }

    init {
        handle.setEventListener(eventListener)

        handle.option("config", "no")
        // handle.option("config-dir", File(filesDir, "mpv_config").absolutePath)
        // handle.option("gpu-shader-cache-dir", File(cacheDir, "mpv_gpu_cache").absolutePath)
        // handle.option("icc-cache-dir", File(cacheDir, "mpv_icc_cache").absolutePath)
        handle.option("profile", "fast")
        handle.option("vo", "gpu-next")

        when (currentPlatform()) {
            is Platform.Android -> {
                handle.option("gpu-context", "android")
                handle.option("opengl-es", "yes")
                handle.option("ao", "audiotrack,opensles")
            }
            is Platform.Windows -> {
                handle.option("gpu-context", "d3d11")
                handle.option("opengl-es", "no")

                handle.option("ao", "audiotrack")
            }
            is Platform.MacOS -> {
                handle.option("gpu-context", "macvk")
                handle.option("opengl-es", "no")

                handle.option("ao", "audiotrack")
            }

            else -> { }
        }
        
        
        handle.option("hwdec", "auto")
        handle.option("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        // handle.option("tls-verify", "yes")
        // handle.option("tls-ca-file", "${this.context.filesDir.path}/cacert.pem")
        handle.option("input-default-bindings", "yes")

        // Limit demuxer cache since the defaults are too high for mobile devices   
        val cacheMegs = if (limitDemuxer()) 32 else 64
        handle.option("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
        handle.option("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")
        // screenshot
        // handle.option("screenshot-directory", screenshotDir.path)
        // workaround for <https://github.com/mpv-player/mpv/issues/14651>
        handle.option("vd-lavc-film-grain", "cpu")

        handle.initialize()

        handle.option("save-position-on-quit", "no")
        handle.option("force-window", "no")
        handle.option("idle", "yes")
        handle.option("keep-open", "always")

        handle.observeProperty("time-pos/full", MPVFormat.MPV_FORMAT_INT64)
        handle.observeProperty("duration/full", MPVFormat.MPV_FORMAT_INT64)
        handle.observeProperty("pause", MPVFormat.MPV_FORMAT_FLAG)
        handle.observeProperty("paused-for-cache", MPVFormat.MPV_FORMAT_FLAG)
        handle.observeProperty("speed", MPVFormat.MPV_FORMAT_STRING) // todo
        
        handle.observeProperty("media-title", MPVFormat.MPV_FORMAT_STRING) // to
        handle.observeProperty("metadata", MPVFormat.MPV_FORMAT_NONE)
        handle.observeProperty("hwdec-current", MPVFormat.MPV_FORMAT_NONE)
    }
    
    @InternalMediampApi
    fun attachRenderSurface(surface: Any): Boolean {
        return attachSurface(handle.ptr, surface)
    }
    
    @InternalMediampApi
    fun detachRenderSurface(): Boolean {
        return detachSurface(handle.ptr)
    }

    override suspend fun setMediaDataImpl(data: MediaData): MPVPlayerData = when (data) {
        is UriMediaData -> {
            val headers = data.headers
            
            // 清除播放列表
            handle.command("stop")
            handle.command("playlist-clear")
            // 设置 headers 和 ua
            handle.option("user-agent", headers["User-Agent"] ?: """Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3""")
            handle.option("http-header-fields-clr", "")
            headers.forEach { (key, value) ->
                handle.option("http-header-fields", "$key: $value")
            }
            
            MPVPlayerData(data, releaseResource = { data.close() })
        }
        is SeekableInputMediaData -> {
            TODO()
        }
    }

    override fun resumeImpl() {
        when (playbackState.value) {
            PlaybackState.READY -> {
                val media = openResource.value ?: return
                handle.option("pause", "true")
                when (val data = media.mediaData) {
                    is UriMediaData -> {
                        handle.command("loadfile", data.uri)
                        playbackState.value = PlaybackState.PLAYING
                    }
                    is SeekableInputMediaData -> TODO()
                    else -> { } // TODO: log unsupported media type
                }
            }
            PlaybackState.PLAYING -> {
                handle.command("cycle", "pause")
            }
            else -> { } // TODO: unreachable
        }
    }

    override fun pauseImpl() {
        if (playbackState.value == PlaybackState.PAUSED) return
        handle.command("cycle", "pause")
    }

    override fun seekTo(positionMillis: Long) {
        handle.command("seek", positionMillis.toString(), "absolute+exact")
        currentPositionMillis.value = positionMillis
    }

    override fun skip(deltaMillis: Long) {
        handle.command("seek", deltaMillis.toString(), "relative+relative")
        currentPositionMillis.value += deltaMillis
    }

    override fun stopPlaybackImpl() {
        handle.command("stop")
        currentPositionMillis.value = 0L
        playbackState.value = PlaybackState.FINISHED
    }
    

    override fun closeImpl() {
        handle.command("stop")
        handle.destroy()
        handle.close()
        
    }
    
    companion object {
        init {
            LibraryLoader.loadLibraries()
        }
    }
}