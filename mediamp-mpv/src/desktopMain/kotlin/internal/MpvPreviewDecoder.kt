/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import kotlinx.coroutines.currentCoroutineContext
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.RenderUpdateListener
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData

private const val FRAME_PREVIEW_LOAD_TARGET_PREFIX = "mediamp://frame_preview/"

/**
 * A minimal, headless mpv instance dedicated to frame-preview extraction.
 *
 * Unlike the full `MpvMediampPlayer`, this registers no event listener, observes no
 * properties and creates no player features: the frame-preview session drives the handle
 * directly and polls the few properties it needs, so property-change events would only
 * add JNI upcall traffic on every seek. The demuxer cache is also kept tiny — this
 * instance only ever decodes single keyframes while paused, and the player's default
 * cache would read ahead tens of MB after every scrub seek, which is hostile to
 * torrent-backed [SeekableInputMediaData] sources.
 */
@OptIn(ExperimentalMediampApi::class)
internal class MpvPreviewDecoder(
    context: Any,
    private val ringBackend: MpvSurfaceRingBackend,
) : AutoCloseable {
    val handle = MPVHandle(context)

    /** Input opened for stream_cb media; owned and closed by this decoder. */
    private var openInput: SeekableInput? = null
    private var loadTarget: String? = null

    init {
        try {
            configure()
            check(ringBackend.createRenderContext(handle.ptr)) {
                "Failed to create the mpv render context for frame preview"
            }
        } catch (e: Throwable) {
            handle.close()
            throw e
        }
    }

    private fun configure() {
        // Mirrored from JvmMpvMediampPlayer.configureNativeHandle — keep the two in sync.
        // Only options that affect decoding or frame correctness are kept; playback-only
        // options (ao, volume-max, input bindings) are intentionally absent.
        handle.option("config", "no")
        handle.option("profile", "fast")
        handle.option("vo", "libmpv")
        // HDR -> SDR tone-mapping, same as the main player (see there for the full
        // rationale) — without it HDR sources produce near-black thumbnails.
        handle.option("gpu-dumb-mode", "no")
        handle.option("target-prim", "bt.709")
        handle.option("target-trc", "srgb")
        handle.option("hwdec", "auto")
        handle.option("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        // workaround for <https://github.com/mpv-player/mpv/issues/14651>
        handle.option("vd-lavc-film-grain", "cpu")

        // Preview-specific: no audio/subtitles, start paused, and bound how much data a
        // single keyframe seek may pull in (the player default of 64 MB would be read
        // ahead after every scrub position).
        handle.option("aid", "no")
        handle.option("sid", "no")
        handle.option("pause", "yes")
        handle.option("demuxer-max-bytes", "${8 * 1024 * 1024}")
        handle.option("demuxer-max-back-bytes", "${4 * 1024 * 1024}")

        handle.initialize()

        handle.option("save-position-on-quit", "no")
        handle.option("force-window", "no")
        handle.option("idle", "yes")
        handle.option("keep-open", "always")
    }

    /**
     * Loads [data] into mpv with playback paused (mirrors the media-loading part of
     * `JvmMpvMediampPlayer.setMediaDataImpl`). Never closes [data] — it is owned by the
     * main player; only the input opened here for stream_cb media is owned by this
     * decoder. Idempotent: a retry after a failed first-frame wait re-issues loadfile.
     */
    suspend fun loadPaused(data: MediaData): Boolean {
        val target = loadTarget ?: when (data) {
            is UriMediaData -> {
                val headers = data.headers.toMutableMap()
                headers.remove("User-Agent")?.let { handle.option("user-agent", it) }
                headers.remove("Referer")?.let { handle.option("referrer", it) }
                val headerFields = headers.entries.joinToString(",") { (key, value) -> "$key: $value" }
                handle.option("http-header-fields", headerFields)
                data.uri
            }

            is SeekableInputMediaData -> {
                val input = data.createInput(currentCoroutineContext())
                val registered = try {
                    handle.registerSeekableInput(input, FRAME_PREVIEW_LOAD_TARGET_PREFIX + data.uri)
                } catch (t: Throwable) {
                    input.close()
                    throw t
                }
                openInput = input
                registered
            }
        }.also { loadTarget = it }

        handle.setPropertyBoolean("pause", true)
        return handle.command("loadfile", target, "replace")
    }

    fun setRenderUpdateListener(listener: RenderUpdateListener?): Boolean =
        handle.setRenderUpdateListener(listener)

    /** See [MpvSurfaceRingBackend.setSurfaceConfig]; the preview ring is always headless. */
    fun requestSurface(width: Int, height: Int): Boolean =
        ringBackend.setSurfaceConfig(handle.ptr, width, height, 0L)

    /** See [MpvSurfaceRingBackend.readSurfacePixels]. */
    fun readSurfacePixels(dims: IntArray): IntArray? =
        ringBackend.readSurfacePixels(handle.ptr, dims)

    override fun close() {
        try {
            handle.setRenderUpdateListener(null)
            ringBackend.setSurfaceConfig(handle.ptr, 0, 0, 0L)
            ringBackend.destroyRenderContext(handle.ptr)
        } catch (_: Exception) {
        }
        try {
            handle.destroy()
        } catch (_: Exception) {
        }
        handle.close()
        try {
            openInput?.close()
        } catch (_: Exception) {
        }
        openInput = null
    }
}
