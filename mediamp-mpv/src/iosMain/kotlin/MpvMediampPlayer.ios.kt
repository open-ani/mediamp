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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.Screenshots
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.features.buildPlayerFeatures
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.mpv.internal.MPV_END_FILE_REASON_EOF
import org.openani.mediamp.mpv.internal.MPV_END_FILE_REASON_ERROR
import org.openani.mediamp.mpv.internal.MpvSessionAdapter
import org.openani.mediamp.mpv.internal.mpvErrorToPlaybackException
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

private const val SEEKABLE_INPUT_LOAD_TARGET_PREFIX = "mediamp://seekble_input_media/"

private fun buildSeekableInputLoadTarget(data: SeekableInputMediaData): String {
    return SEEKABLE_INPUT_LOAD_TARGET_PREFIX + data.uri
}

/**
 * The iOS mpv backend — currently **audio-only**.
 *
 * mpv on iOS has no render-context implementation yet ([attachSurface]/[detachSurface] are
 * unimplemented), so this backend constructs in the declared video-disabled mode of spec §6:
 * `vo=null`. Video tracks are decoded but never displayed; audio playback is fully
 * functional. AVKit (`mediamp-avkit`) is the production iOS backend; displaying video here
 * requires a Metal render-context implementation (future work). Rationale for `vo=null`
 * (spec §6): with `vo=libmpv` and no render context, `loadfile` would permanently kill the
 * session's video track (vo preinit fails → video deselected) and video-only files would
 * fail to open entirely — `vo=null` keeps every source openable.
 *
 * All state transitions are owned by [AbstractMediampPlayer] (the single-writer machine of
 * `docs/playback-state-v2.md`); this class implements the backend SPI with exactly the same
 * mpv event/property mapping as the shared JVM backend (`JvmMpvMediampPlayer` in `jvmMain`) —
 * only the native interop layer differs:
 *
 * - The Ready point of an open is `MPV_EVENT_FILE_LOADED`; `loadfile` is issued during
 *   [openImpl] with the requested `pause` level and start position applied natively first,
 *   so a bad source fails inside `setMediaData` (spec §3).
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
 * Capability notes (spec §6): mpv cannot measure data starvation while user-paused —
 * the `paused-stall` capability is degraded; `isStalled` is authoritative only while the
 * native transport is playing.
 *
 * Known gaps versus the JVM backend (the iOS native binding lacks these pieces):
 * - No video output (see above): no render surface attachment and no render-context
 *   lifecycle; [openImpl] does not wait for a render context.
 * - No [org.openani.mediamp.features.FramePreview] (JVM-only decoder).
 * - [Screenshots] uses mpv's `screenshot-to-file` command, which cannot convert hwdec
 *   frames on all builds (the JVM desktop backend has a native surface-ring readback).
 */
@OptIn(InternalMediampApi::class, InternalForInheritanceMediampApi::class, ExperimentalMediampApi::class)
actual class MpvMediampPlayer(
    context: Any = Unit,
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : AbstractMediampPlayer(
    parentCoroutineContext = parentCoroutineContext,
    mainDispatcher = mainDispatcher,
) {
    internal val handle by lazy { MPVHandle(context) }

    actual override val impl: Any get() = handle

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
     * late per-session resource closers must not touch the handle.
     */
    @Volatile
    private var nativeTeardownStarted = false

    /**
     * Parent of the per-session await jobs passed to [SeekableInputMediaData.createInput]
     * in [openImpl]. Cancelled first thing in [closeImpl]: a read still blocked in an await
     * context holds the native stream lock, which the stream close inside `handle.destroy()`
     * needs — the read must be unblocked before native teardown can join mpv's threads.
     */
    private val inputAwaitParent = SupervisorJob(parentCoroutineContext[Job])

    private val audioLevelController = MpvAudioLevelController(handle)
    private val buffering = MpvBuffering(state)
    private val screenshots = MpvScreenshots { path -> takeScreenshotImpl(path) }
    private val videoAspectRatio = MpvVideoAspectRatio(handle)
    private val mediaMetadata = MpvMediaMetadata(handle)

    actual override val features: PlayerFeatures = buildPlayerFeatures {
        add(PlaybackSpeed.Key, machinePlaybackSpeed())
        add(AudioLevelController.Key, audioLevelController)
        add(Buffering.Key, buffering)
        add(Screenshots.Key, screenshots)
        add(VideoAspectRatio.Key, videoAspectRatio)
        add(MediaMetadata, mediaMetadata)
    }

    private val eventListener = object : EventListener {
        override fun onPropertyChange(name: String) {
            if (nativeTeardownStarted) return
            when (name) {
                "track-list" -> mediaMetadata.refreshTracks()
                "chapter-list" -> mediaMetadata.refreshChapters()
            }
        }

        override fun onPropertyChange(name: String, value: Boolean) {
            if (nativeTeardownStarted) return
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

    /**
     * Writes the current video frame to [path] as an image using mpv's screenshot command,
     * which cannot convert hwdec frames on all builds (see the class KDoc gap note).
     */
    private suspend fun takeScreenshotImpl(path: String): Boolean {
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

        // iOS is audio-only for now: declared video-disabled mode (spec §6). There is no
        // render-context implementation on iOS, and with vo=libmpv a `loadfile` without a
        // render context permanently kills the session's video track (video-only files
        // fail to open entirely). vo=null keeps every source openable; video tracks are
        // decoded but never displayed. Audio goes through AudioUnit.
        handle.option("ao", "audiounit")
        handle.option("vo", "null")

        handle.option("hwdec", "auto") // auto picks videotoolbox on iOS
        handle.option("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        // Prefer libdav1d for software AV1 (see the JVM backend for rationale); harmless on
        // runtimes without libdav1d.
        handle.option("vd", "libdav1d")
        handle.option("input-default-bindings", "no")
        handle.option("volume-max", "200")

        // Limit demuxer cache since the defaults are too high for mobile devices
        val cacheMegs = 32
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

    // region SPI

    override suspend fun openImpl(
        data: MediaData,
        session: PlaybackSessionHandle,
        playWhenReady: Boolean,
        startPositionMillis: Long,
    ): OpenResult {
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
                // The input's reads run on mpv demux threads for the whole session, and a
                // read that must wait for data (e.g. a torrent input awaiting an
                // undownloaded piece) blocks inside the await context passed here. This
                // open coroutine's own job completes right after the open, so it must not
                // be that context: every later wait would fail instantly with "Parent job
                // is Completed" and surface as a spurious EOF. Hand the input a
                // session-lifetime job instead.
                val awaitJob = SupervisorJob(inputAwaitParent)
                val input = try {
                    data.createInput(Dispatchers.IO + awaitJob)
                } catch (t: Throwable) {
                    awaitJob.cancel()
                    throw t
                }
                val registered = try {
                    handle.registerSeekableInput(input, target)
                } catch (t: Throwable) {
                    awaitJob.cancel()
                    input.close()
                    throw t
                }
                // The native registry owns (and closes) the SeekableInput. Once close()
                // started native teardown the registry is torn down wholesale and the
                // handle must not be touched anymore.
                sessionResources = AutoCloseable {
                    // Cancel before unregistering: a blocked read holds the native stream
                    // lock that the stream close behind unregistration waits for.
                    awaitJob.cancel()
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

    @OptIn(DelicateCoroutinesApi::class)
    override fun closeImpl() {
        nativeTeardownStarted = true
        // Unblock reads still parked in a session await context before native teardown
        // joins mpv's demux threads (a blocked read holds the native stream lock).
        inputAwaitParent.cancel()
        sessionAdapter = null
        mediaMetadata.clear()
        // Released is already committed and the session detached; do the heavy native
        // teardown off the machine thread (spec §4): mpv destruction joins the native event
        // thread, which must not hang the UI thread.
        GlobalScope.launch(Dispatchers.Default) {
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
}

private fun formatSeconds(seconds: Double): String {
    // mpv parses decimal seconds; format manually to stay locale-independent.
    val totalMillis = (seconds * 1000).toLong()
    val whole = totalMillis / 1000
    val fraction = (totalMillis % 1000).toInt()
    return if (fraction == 0) {
        whole.toString()
    } else {
        "$whole.${fraction.toString().padStart(3, '0').trimEnd('0')}"
    }
}
