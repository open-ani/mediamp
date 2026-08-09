/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import org.openani.mediamp.AbstractMediampPlayer
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.OpenResult
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.PlaybackSessionHandle
import org.openani.mediamp.TransportSnapshot
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.FramePreview
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.Screenshots
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.features.buildPlayerFeatures
import org.openani.mediamp.internal.Arch
import org.openani.mediamp.internal.Platform
import org.openani.mediamp.internal.currentPlatform
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.mpv.internal.MPV_END_FILE_REASON_EOF
import org.openani.mediamp.mpv.internal.MPV_END_FILE_REASON_ERROR
import org.openani.mediamp.mpv.internal.MpvSessionAdapter
import org.openani.mediamp.mpv.internal.mpvErrorToPlaybackException
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

private const val SEEKABLE_INPUT_LOAD_TARGET_PREFIX = "mediamp://seekble_input_media/"

private fun buildSeekableInputLoadTarget(data: SeekableInputMediaData): String {
    return SEEKABLE_INPUT_LOAD_TARGET_PREFIX + data.uri
}

/**
 * The shared JVM (desktop + Android) mpv backend.
 *
 * All state transitions are owned by [AbstractMediampPlayer] (the single-writer machine of
 * `docs/playback-state-v2.md`); this class only implements the backend SPI and reports native
 * facts through the per-session [PlaybackSessionHandle]:
 *
 * - The Ready point of an open is `MPV_EVENT_FILE_LOADED`; `loadfile` is issued during
 *   [openImpl] with the requested `pause` level and start position applied natively first,
 *   so a bad source fails inside `setMediaData` (spec §3 — v1's defer-loadfile-to-resume is
 *   abolished).
 * - Transport levels (`pause`, `paused-for-cache`) are reported level-triggered, plus a
 *   read-after-command report after every [playImpl]/[pauseImpl] (spec §5).
 * - `eof-reached` rising edge is the Ended fact; the keep-open auto-pause it entails is part
 *   of that fact and never reported as a transport change.
 * - Machine-issued seeks are completed by the `MPV_EVENT_SEEK` → `MPV_EVENT_PLAYBACK_RESTART`
 *   pair with latest-generation attribution (see [MpvSessionAdapter]): a restart with no
 *   machine-attributable `SEEK` before it — the initial-load restart, or the open's own
 *   `start=` positioning — completes nothing. A synchronously rejected `seek` command
 *   synthesizes its completion so the seek gate can never wedge.
 * - `END_FILE` events are attributed by playlist entry id: a queued `END_FILE` of a
 *   previously unloaded file (episode switch) cannot fail or end the session that
 *   replaced it.
 *
 * Capability notes (spec §6): mpv cannot measure data starvation while user-paused
 * (`paused-for-cache` does not engage at pause) — the `paused-stall` capability is degraded;
 * `isStalled` is authoritative only while the native transport is playing. On Linux the
 * `surface-independent-open` capability is degraded: [openImpl] suspends until the GLX render
 * context exists (see [ensureRenderContextForLoad]).
 */
@kotlin.OptIn(InternalMediampApi::class, InternalForInheritanceMediampApi::class, ExperimentalMediampApi::class)
abstract class JvmMpvMediampPlayer(
    context: Any,
    parentCoroutineContext: CoroutineContext,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : AbstractMediampPlayer(
    parentCoroutineContext = parentCoroutineContext,
    mainDispatcher = mainDispatcher,
) {
    internal val handle by lazy { MPVHandle(context) }

    override val impl: Any get() = handle

    /**
     * The active media session as seen by the persistent event listener; `null` while no
     * session is bound. Written on the machine thread, read from the mpv event thread.
     */
    @Volatile
    private var sessionAdapter: MpvSessionAdapter? = null

    /**
     * The highest playlist entry id observed so far (mpv ids are monotonically increasing).
     * Written only on the mpv event thread; read on the machine thread at [openImpl] to seed
     * the new session's stale-`END_FILE` ceiling (see [MpvSessionAdapter.isStaleEndFile]).
     */
    @Volatile
    private var maxSeenPlaylistEntryId = 0L

    /**
     * Set once [closeImpl] starts native teardown: the event listener stops dispatching and
     * late per-session resource closers must not touch the handle (the native destructor
     * closes all stream_cb registrations itself).
     */
    @Volatile
    private var nativeTeardownStarted = false

    /** Bumped by [renderContextBecameReady]; [awaitRenderContextForLoad] waits on it. */
    private val renderContextReadySignal = MutableStateFlow(0L)

    private val audioLevelController = MpvAudioLevelController(handle)
    private val buffering = MpvBuffering(state)
    private val screenshots = MpvScreenshots { path -> takeScreenshotImpl(path) }
    private val videoAspectRatio = MpvVideoAspectRatio(handle)
    private val mediaMetadata = MpvMediaMetadata(handle)
    private val framePreview: FramePreview? = createMpvFramePreview(this, context, parentCoroutineContext)

    override val features: PlayerFeatures = buildPlayerFeatures {
        add(PlaybackSpeed.Key, machinePlaybackSpeed())
        add(AudioLevelController.Key, audioLevelController)
        add(Buffering.Key, buffering)
        add(Screenshots.Key, screenshots)
        add(VideoAspectRatio.Key, videoAspectRatio)
        add(MediaMetadata, mediaMetadata)
        framePreview?.let { add(FramePreview.Key, it) }
    }

    private val eventListener = object : EventListener {
        private val debugProps = System.getProperty("mediamp.mpv.debug.props") != null
        private fun dbg(kind: String, name: String, value: Any?) {
            if (debugProps) System.err.println("[mpv-prop] $kind $name = $value (adapter=${sessionAdapter != null})")
        }

        override fun onPropertyChange(name: String) {
            if (nativeTeardownStarted) return
            dbg("none", name, null)
            when (name) {
                "track-list" -> mediaMetadata.refreshTracks()
                "chapter-list" -> mediaMetadata.refreshChapters()
            }
        }

        override fun onPropertyChange(name: String, value: Boolean) {
            if (nativeTeardownStarted) return
            dbg("bool", name, value)
            when (name) {
                "pause" -> {
                    val adapter = sessionAdapter ?: return
                    // The keep-open auto-pause at EOF is part of the Ended fact and must not
                    // be reported as a transport change (spec §5). eof-reached is read live
                    // because mpv may deliver this notification before the eof-reached one.
                    if (value && (adapter.eofReached || handle.getPropertyBoolean("eof-reached"))) return
                    adapter.session.reportTransport(liveTransportSnapshot())
                }

                "paused-for-cache" -> {
                    sessionAdapter?.session?.reportTransport(liveTransportSnapshot())
                }

                "mute" -> audioLevelController.onMuteChanged(value)

                "eof-reached" -> {
                    val adapter = sessionAdapter ?: return
                    if (adapter.onEofReachedChanged(value)) {
                        adapter.session.notifyEnded()
                    }
                }
            }
        }

        override fun onPropertyChange(name: String, value: Long) {
            if (nativeTeardownStarted) return
            when (name) {
                "cache-buffering-state" -> buffering.bufferedPercentage.value = value.toInt().coerceIn(0, 100)
            }
        }

        override fun onPropertyChange(name: String, value: Double) {
            if (nativeTeardownStarted) return
            dbg("double", name, value)
            when (name) {
                "time-pos" -> {
                    // Stale pre-seek reports are dropped by the machine's seek gating.
                    sessionAdapter?.session?.notifyPosition((value * 1000).toLong().coerceAtLeast(0L))
                }

                "duration" -> {
                    val adapter = sessionAdapter ?: return
                    adapter.lastDurationMillis = (value * 1000).toLong().takeIf { it > 0 } // unknown -> null
                    adapter.session.notifyProperties(MediaProperties(adapter.lastTitle, adapter.lastDurationMillis))
                }

                "volume" -> audioLevelController.onVolumeChanged(value)
            }
        }

        override fun onPropertyChange(name: String, value: String) {
            if (nativeTeardownStarted) return
            when (name) {
                "media-title" -> {
                    val adapter = sessionAdapter ?: return
                    adapter.lastTitle = value
                    adapter.session.notifyProperties(MediaProperties(adapter.lastTitle, adapter.lastDurationMillis))
                }
            }
        }

        override fun onEvent(event: Int) {
            if (nativeTeardownStarted) return
            val adapter = sessionAdapter ?: return
            when (event) {
                MPVEvent.FILE_LOADED -> {
                    adapter.onFileLoaded()
                    adapter.pendingOpen?.complete(Unit)
                }

                MPVEvent.SEEK -> adapter.onSeekEvent()

                MPVEvent.PLAYBACK_RESTART -> {
                    // Seek completion — but only when a machine-issued generation was
                    // stamped by a preceding MPV_EVENT_SEEK: mpv also fires
                    // playback-restart at initial file load and after the open's own
                    // `start=` positioning. mpv coalesces rapid seeks into one restart;
                    // the stamped generation is the latest issued at SEEK-processing time,
                    // closing every superseded generation at once (spec §5).
                    val generation = adapter.onPlaybackRestart()
                    if (generation != 0) {
                        adapter.session.notifySeekCompleted(
                            generation,
                            currentNativePositionMillis(),
                            liveTransportSnapshot(),
                        )
                    }
                }
            }
        }

        override fun onStartFile(playlistEntryId: Long) {
            if (nativeTeardownStarted) return
            if (playlistEntryId > maxSeenPlaylistEntryId) {
                maxSeenPlaylistEntryId = playlistEntryId
            }
            // Fallback binding for natives that could not resolve `playlist/0/id` in
            // openImpl; normally a no-op re-store of the same id.
            sessionAdapter?.bindEntryId(playlistEntryId)
        }

        override fun onEndFile(reason: Int, mpvError: Int, playlistEntryId: Long) {
            if (nativeTeardownStarted) return
            val adapter = sessionAdapter ?: return
            if (playlistEntryId > maxSeenPlaylistEntryId) {
                maxSeenPlaylistEntryId = playlistEntryId
            }
            // Entry-id attribution: a queued END_FILE of a previously unloaded file (e.g.
            // the END_FILE(STOP) that `stop` emits for the old episode) must not fail the
            // open, end, or error the session that replaced it.
            if (adapter.isStaleEndFile(playlistEntryId)) return
            when (reason) {
                MPV_END_FILE_REASON_ERROR -> {
                    val error = mpvErrorToPlaybackException(mpvError)
                    // Before FILE_LOADED this fails the open (setMediaData throws, spec §3);
                    // after it, it is an asynchronous mid-session failure.
                    if (adapter.pendingOpen?.completeExceptionally(error) != true) {
                        adapter.session.notifyError(error)
                    }
                }

                MPV_END_FILE_REASON_EOF -> {
                    // keep-open=always normally reports natural EOF via 'eof-reached'; this
                    // covers configurations where the file still unloads at EOF.
                    if (adapter.pendingOpen?.completeExceptionally(
                            PlaybackException(
                                PlaybackErrorCode.INTERNAL,
                                "mpv unloaded the file (EOF) before it finished opening",
                            ),
                        ) != true
                    ) {
                        adapter.session.notifyEnded()
                    }
                }

                else -> {
                    // STOP/QUIT/REDIRECT: mid-session these are machine-initiated (stop/close)
                    // and already handled; during an open the file can no longer reach
                    // FILE_LOADED, so fail the open. (When the machine itself cancelled the
                    // open, the awaiting coroutine is already cancelled and this is a no-op.)
                    adapter.pendingOpen?.completeExceptionally(
                        PlaybackException(
                            PlaybackErrorCode.INTERNAL,
                            "mpv unloaded the file before it finished opening (reason=$reason)",
                        ),
                    )
                }
            }
        }
    }

    internal fun setRenderUpdateListener(listener: RenderUpdateListener?): Boolean {
        return handle.setRenderUpdateListener(listener)
    }

    /**
     * Writes the current video frame to [path] as an image. The default uses mpv's
     * screenshot command, which cannot convert hwdec frames on all builds; platform
     * subclasses may override with a native readback.
     */
    protected open suspend fun takeScreenshotImpl(path: String): Boolean {
        return handle.command("screenshot-to-file", path, "video")
    }

    init {
        // Resolve the native handle now: if its creation fails, nMake throws with nothing
        // to release. If any later configuration step fails (e.g. initialize() throwing),
        // close the handle so a failed construction never leaks the native mpv instance,
        // then rethrow for the caller to handle.
        val nativeHandle = handle
        try {
            configureNativeHandle(nativeHandle)
        } catch (e: Throwable) {
            nativeHandle.close()
            throw e
        }
    }

    private fun configureNativeHandle(handle: MPVHandle) {
        handle.setEventListener(eventListener)

        handle.option("config", "no")
        handle.option("profile", "fast")

        when (currentPlatform()) {
            is Platform.Android -> {
                handle.option("gpu-context", "android")
                handle.option("opengl-es", "yes")
                handle.option("ao", "audiotrack,opensles")
                handle.option("vo", "gpu-next")
            }

            is Platform.Windows -> {
                // The desktop render path drives the libmpv D3D11 render API on its own
                // ID3D11Device (render_d3d11.cpp); gpu-context is not used with vo=libmpv.
                handle.option("ao", "wasapi")
                handle.option("vo", "libmpv")
            }

            is Platform.MacOS -> {
                // The render API provides its own offscreen CGL context (render_macos.mm);
                // gpu-context is not used with vo=libmpv.
                handle.option("ao", "coreaudio")
                handle.option("vo", "libmpv")
            }

            is Platform.Linux -> {
                // The desktop GLX bridge creates libmpv's OpenGL render context only after
                // Skiko exposes its live share context; ao is picked at playback time.
                handle.option("ao", "pulse,alsa")
                handle.option("vo", "libmpv")
            }

            else -> {}
        }

        when (currentPlatform()) {
            is Platform.Windows, is Platform.MacOS, is Platform.Linux -> {
                // HDR -> SDR tone-mapping. The desktop render API (render_d3d11.cpp /
                // render_macos.mm) draws into an 8-bit SDR (sRGB) texture. By default
                // mpv's renderer enters "dumb mode" (a fast passthrough blit) and writes
                // HDR (PQ/bt2020) frames untonemapped — everything but the brightest
                // highlights is crushed to black (symptom: an HDR video plays with audio
                // but a near-black picture). check_dumb_mode() only inspects scaling /
                // debanding / shaders, never the target colorspace, so the target-*
                // options below cannot leave dumb mode on their own; gpu-dumb-mode=no
                // forces the full color-management path. target-prim/target-trc then
                // declare an SDR output so HDR is tone-mapped down to it. Values use
                // libplacebo naming ("bt.709", not "bt709"). This is a no-op for SDR
                // sources (target matches source). Android/iOS are excluded: Android
                // uses vo=gpu-next, which does its own HDR handling.
                handle.option("gpu-dumb-mode", "no")
                handle.option("target-prim", "bt.709")
                handle.option("target-trc", "srgb")
            }

            else -> {}
        }

        handle.option("hwdec", "auto")
        if (currentPlatform().let { it is Platform.Windows && it.arch == Arch.AARCH64 }) {
            // TODO: restore multi-threaded hwdec once FFmpeg fixes the race this works
            // around: dxva2/d3d11va hwaccels attach the decoder interface ref to
            // frame->buf[1] in end_frame, and FFmpeg frame threading copies that frame
            // concurrently (update_thread_context DPB copy). The AVBufferRef publication
            // is unfenced, so on ARM64's weak memory model the copy can observe the
            // pointer before the ref contents, yielding a blank ref and crashing
            // av_buffer_replace (NULL AVBuffer->refcount). x86 TSO hides this.
            handle.option("hwdec-threads", "1")
        }
        handle.option("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        // FFmpeg 8 removed the native AV1 software decoder: the remaining "av1" decoder
        // is a hwaccel-only shim (upstream registers it after the external decoders with
        // "hwaccel hooks only, so prefer external decoders"). Software AV1 therefore goes
        // through libdav1d, which is statically linked into our desktop FFmpeg builds.
        // State the preference explicitly so decoder selection does not silently depend
        // on FFmpeg's registration order; harmless on runtimes without libdav1d, and the
        // hwdec path is unaffected (it binds hwaccels to the native "av1" decoder).
        handle.option("vd", "libdav1d")
        handle.option("input-default-bindings", "no")
        handle.option("volume-max", "200")

        // Limit demuxer cache since the defaults are too high for mobile devices
        val cacheMegs = if (limitDemuxer()) 32 else 64
        handle.option("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
        handle.option("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")
        // workaround for <https://github.com/mpv-player/mpv/issues/14651>
        handle.option("vd-lavc-film-grain", "cpu")

        handle.initialize()

        handle.option("save-position-on-quit", "no")
        handle.option("force-window", "no")
        handle.option("idle", "yes")
        handle.option("keep-open", "always")

        handle.observeProperty("eof-reached", MPVFormat.MPV_FORMAT_FLAG)
        handle.observeProperty("time-pos", MPVFormat.MPV_FORMAT_DOUBLE)
        handle.observeProperty("duration", MPVFormat.MPV_FORMAT_DOUBLE)
        handle.observeProperty("pause", MPVFormat.MPV_FORMAT_FLAG)
        handle.observeProperty("paused-for-cache", MPVFormat.MPV_FORMAT_FLAG)
        handle.observeProperty("volume", MPVFormat.MPV_FORMAT_DOUBLE)
        handle.observeProperty("mute", MPVFormat.MPV_FORMAT_FLAG)
        handle.observeProperty("cache-buffering-state", MPVFormat.MPV_FORMAT_INT64)
        handle.observeProperty("media-title", MPVFormat.MPV_FORMAT_STRING)
        handle.observeProperty("track-list", MPVFormat.MPV_FORMAT_NONE)
        handle.observeProperty("chapter-list", MPVFormat.MPV_FORMAT_NONE)
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

    /**
     * Whether the producer render context may exist for `loadfile` right now. Platform
     * subclasses whose render context is unavailable until an external render environment is
     * attached (Linux/GLX) return `false`; [openImpl] then suspends (holding the machine in
     * Opening — spec §6, degraded `surface-independent-open`) until [renderContextBecameReady].
     */
    protected open fun ensureRenderContextForLoad(): Boolean = true

    /**
     * Environment-bound render backends reject render-environment replacement while a
     * playback session is active.
     */
    protected fun hasActivePlaybackSession(): Boolean = sessionAdapter != null

    /** Wakes an [openImpl] suspended waiting for the render context. */
    protected fun renderContextBecameReady() {
        renderContextReadySignal.update { it + 1 }
    }

    private suspend fun awaitRenderContextForLoad() {
        while (true) {
            // Capture the signal before probing so a callback firing between the probe and
            // the wait cannot be missed.
            val seen = renderContextReadySignal.value
            if (ensureRenderContextForLoad()) return
            renderContextReadySignal.first { it != seen }
        }
    }

    // region SPI

    override suspend fun openImpl(
        data: MediaData,
        session: PlaybackSessionHandle,
        playWhenReady: Boolean,
        startPositionMillis: Long,
    ): OpenResult {
        // Linux/GLX (spec §6): with vo=libmpv, `loadfile` before the render context exists
        // permanently disables the video track, and the producer context can only join
        // Skiko's live GLX share group. Suspend here (machine stays in Opening) until the
        // surface attach provides it. Eager platforms (macOS Metal, Windows D3D11) and
        // headless modes proceed immediately.
        awaitRenderContextForLoad()

        buffering.bufferedPercentage.value = 0

        var sessionResources: AutoCloseable? = null
        val loadTarget: String = when (data) {
            is UriMediaData -> {
                val headers = data.headers.toMutableMap()
                headers.remove("User-Agent")?.let { handle.option("user-agent", it) }
                headers.remove("Referer")?.let { handle.option("referrer", it) }
                val headerFields = headers.entries.joinToString(",") { (key, value) -> "$key: $value" }
                handle.option("http-header-fields", headerFields)
                data.uri
            }

            is SeekableInputMediaData -> {
                val target = buildSeekableInputLoadTarget(data)
                val input = data.createInput(currentCoroutineContext())
                val registered = try {
                    handle.registerSeekableInput(input, target)
                } catch (t: Throwable) {
                    input.close()
                    throw t
                }
                // The native registry owns (and closes) the SeekableInput. Once close()
                // started native teardown the registry is torn down wholesale and the
                // handle must not be touched anymore.
                sessionResources = AutoCloseable {
                    if (!nativeTeardownStarted) {
                        runCatching { handle.unregisterSeekableInput(registered) }
                    }
                }
                registered
            }
        }

        val adapter = MpvSessionAdapter(
            session,
            openStartSeekExpected = startPositionMillis > 0,
            staleEntryIdCeiling = maxSeenPlaylistEntryId,
        )
        val opened = CompletableDeferred<Unit>()
        adapter.pendingOpen = opened
        try {
            // Apply the requested intent natively BEFORE loadfile (spec §5 open handoff):
            // the file starts in the requested transport state instead of being toggled
            // afterwards.
            handle.setPropertyBoolean("pause", !playWhenReady)
            sessionAdapter = adapter

            // The start position is applied as part of the open itself (spec §3) — it is
            // not a seek and involves no seek generation. mpv clamps it into the file.
            val loaded = if (startPositionMillis > 0) {
                handle.command(
                    "loadfile", loadTarget, "replace", "-1",
                    "start=${formatSeconds(startPositionMillis / 1000.0)}",
                )
            } else {
                handle.command("loadfile", loadTarget, "replace")
            }
            if (!loaded) {
                throw PlaybackException(
                    PlaybackErrorCode.INTERNAL,
                    "mpv rejected the 'loadfile' command for $loadTarget",
                )
            }
            // The entry id of the just-loaded file, read synchronously (`loadfile ...
            // replace` leaves exactly this entry in the playlist): authoritative binding
            // for END_FILE attribution even before this session's START_FILE event is
            // delivered. 0 on failure -> the START_FILE fallback binds instead.
            adapter.bindEntryId(handle.getPropertyInt("playlist/0/id").toLong())

            // Ready point (spec §3): MPV_EVENT_FILE_LOADED — the source is accepted and
            // metadata is available. An END_FILE arriving first fails the open with a
            // mapped PlaybackException.
            opened.await()

            val durationMillis = (handle.getPropertyDouble("duration") * 1000).toLong().takeIf { it > 0 }
            val title = handle.getPropertyString("media-title")
            adapter.lastDurationMillis = durationMillis
            adapter.lastTitle = title
            val atEnd = handle.getPropertyBoolean("eof-reached")
            if (atEnd) {
                // Already at EOF (start position at/beyond the end): this IS the Ended fact;
                // suppress the redundant rising-edge notification.
                adapter.eofReached = true
            }
            return OpenResult(
                sessionResources = sessionResources,
                initialSnapshot = liveTransportSnapshot(),
                atEnd = atEnd,
                initialProperties = MediaProperties(title = title, durationMillis = durationMillis),
            )
        } catch (e: Throwable) {
            // Open failure or suspend-cancellation: unload whatever loadfile started and
            // release the adapter-owned per-session resources (the machine only owns them
            // once OpenResult is returned). The machine releases the MediaData itself.
            if (sessionAdapter === adapter) {
                sessionAdapter = null
            }
            runCatching { handle.command("stop") }
            runCatching { sessionResources?.close() }
            throw e
        }
    }

    override fun playImpl() {
        handle.setPropertyBoolean("pause", false)
        reportTransportAfterCommand()
    }

    override fun pauseImpl() {
        handle.setPropertyBoolean("pause", true)
        reportTransportAfterCommand()
    }

    override fun seekImpl(positionMillis: Long, seekGeneration: Int) {
        val adapter = sessionAdapter ?: return
        adapter.onSeekIssued(seekGeneration)
        val targetSeconds = positionMillis.coerceAtLeast(0L) / 1000.0
        if (!handle.command("seek", formatSeconds(targetSeconds), "absolute+exact")) {
            // Synchronous refusal (unseekable/live media): the seek gate must never wedge
            // (spec §5) — synthesize the completion at the actual native position, stamped
            // with the issued generation.
            if (adapter.onSeekRejected(seekGeneration)) {
                adapter.session.notifySeekCompleted(
                    seekGeneration,
                    currentNativePositionMillis(),
                    liveTransportSnapshot(),
                )
            }
        }
    }

    override fun setRateImpl(rate: Float) {
        handle.setPropertyDouble("speed", rate.toDouble())
    }

    override fun stopImpl() {
        sessionAdapter = null
        handle.command("stop")
        mediaMetadata.clear()
        buffering.bufferedPercentage.value = 0
    }

    override fun closeImpl() {
        nativeTeardownStarted = true
        sessionAdapter = null
        (framePreview as? AutoCloseable)?.close()
        mediaMetadata.clear()
        // Released is already committed and the session detached; do the heavy native
        // teardown off the machine thread (spec §4): mpv destruction joins the native event
        // thread, which used to hang the UI thread (v1 defect M8).
        thread(name = "mediamp-mpv-teardown") {
            runCatching { handle.command("stop") }
            runCatching { handle.destroy() }
            runCatching { handle.close() }
        }
    }
    // endregion

    /**
     * Read-after-command (spec §5): report the actual native transport level after every
     * play/pause command — even when the command was a native no-op or failed —
     * reconciliation converges on observations, never on expectations.
     */
    private fun reportTransportAfterCommand() {
        sessionAdapter?.session?.reportTransport(liveTransportSnapshot())
    }

    /**
     * A fresh transport observation read directly from mpv (thread-safe from any thread).
     *
     * `paused-for-cache` is mpv's only starvation signal and does not engage while
     * user-paused: the `paused-stall` capability is degraded (spec §6). It is authoritative
     * while the transport is playing, so a stall with play intent is never reported as
     * `isStalled = false`.
     */
    private fun liveTransportSnapshot(): TransportSnapshot = TransportSnapshot(
        nativePlayWhenReady = !handle.getPropertyBoolean("pause"),
        isStalled = handle.getPropertyBoolean("paused-for-cache"),
    )

    private fun currentNativePositionMillis(): Long =
        (handle.getPropertyDouble("time-pos") * 1000).toLong().coerceAtLeast(0L)

    /**
     * Returns the media currently loaded, or `null`.
     * Used by the frame-preview decoder to mirror the main player's media.
     */
    internal fun currentMediaDataOrNull(): MediaData? = mediaData.value
}

/**
 * Creates the platform [FramePreview] implementation for this player, or `null` when the
 * platform has no frame-preview support (e.g. Android, which uses ExoPlayer in practice).
 *
 * Called during the player's construction: implementations must only capture references and
 * must not call back into [player] until the first frame request.
 */
internal expect fun createMpvFramePreview(
    player: JvmMpvMediampPlayer,
    context: Any,
    parentCoroutineContext: CoroutineContext,
): FramePreview?

private fun formatSeconds(seconds: Double): String {
    // mpv parses decimal seconds; String.format would be locale-sensitive.
    return ((seconds * 1000).toLong() / 1000.0).toBigDecimal().toPlainString()
}

expect fun limitDemuxer(): Boolean
