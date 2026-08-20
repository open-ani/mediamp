/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:kotlin.OptIn(InternalMediampApi::class)

package org.openani.mediamp.exoplayer

import android.content.Context
import android.net.Uri
import android.util.Pair
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.annotation.UiThread
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.TrackSelection
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openani.mediamp.AbstractMediampPlayer
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.OpenResult
import org.openani.mediamp.PlaybackSessionHandle
import org.openani.mediamp.PlayerState
import org.openani.mediamp.TransportSnapshot
import org.openani.mediamp.exoplayer.internal.ExoFramePreview
import org.openani.mediamp.exoplayer.internal.SeekableInputDataSource
import org.openani.mediamp.exoplayer.internal.WsolaRenderersFactory
import org.openani.mediamp.exoplayer.internal.toPlaybackException
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.FramePreview
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.features.buildPlayerFeatures
import org.openani.mediamp.internal.MutableTrackGroup
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.Chapter
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.metadata.TrackLabel
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import androidx.media3.common.PlaybackException as Media3PlaybackException
import androidx.media3.common.Player as Media3Player


/**
 * ExoPlayer (media3) backend for Mediamp, implementing the v2 state specification
 * (`docs/playback-state-v2.md`).
 *
 * The [AbstractMediampPlayer] state machine is the single writer of all public flows; this
 * backend only executes native commands (`*Impl`) and reports native facts (transport levels,
 * seek completions, end-of-media, errors, properties, positions) through the current
 * [PlaybackSessionHandle].
 *
 * Backend notes:
 * - The Ready point of an open (spec §3) is the first `onTracksChanged` callback with
 *   non-empty tracks, which fires after the container is parsed — provably after real I/O.
 *   A failing source therefore fails inside [setMediaData]; the media source factories use a
 *   phase-aware [LoadErrorHandlingPolicy] that retries minimally until the Ready point (so
 *   open failures surface promptly) and applies media3's default retry schedule afterwards
 *   (so mid-playback transient load errors are not fatal).
 * - Seek completions are media3's masked `onPositionDiscontinuity(DISCONTINUITY_REASON_SEEK)`,
 *   which fires at `seekTo` call time state-independently, so the seek gate cannot wedge.
 * - `STATE_ENDED` is forwarded as the Ended fact even for a paused seek-to-end (Ended-on-seek
 *   is normalized by the spec, §6).
 *
 * @param mediaSourceInterceptor optional hook (spec §11) invoked on the main dispatcher during
 *   each open, after the [MediaSource] is built and before it is set on the player. Use it to
 *   wrap or replace the source (e.g. subtitle burn-in pipelines) without racing the open.
 *
 * @see ExoPlayerMediampPlayerFactory
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(InternalMediampApi::class, InternalForInheritanceMediampApi::class, ExperimentalMediampApi::class)
public class ExoPlayerMediampPlayer @UiThread public constructor(
    context: Context,
    parentCoroutineContext: CoroutineContext,
    audioTimeStretch: ExoPlayerAudioTimeStretch = ExoPlayerAudioTimeStretch.Media3Default,
    private val mediaSourceInterceptor: ((MediaSource, MediaData) -> MediaSource)? = null,
) : AbstractMediampPlayer(
    parentCoroutineContext = parentCoroutineContext,
    mainDispatcher = Dispatchers.Main.immediate,
) {

    // Keep the previous two-argument JVM constructor for binary compatibility. A Kotlin default
    // argument lets recompiled source omit the trailing arguments, but does not preserve the old
    // JVM constructor descriptors used by already-compiled callers.
    @UiThread
    public constructor(
        context: Context,
        parentCoroutineContext: CoroutineContext,
    ) : this(context, parentCoroutineContext, ExoPlayerAudioTimeStretch.Media3Default, null)

    @UiThread
    public constructor(
        context: Context,
        parentCoroutineContext: CoroutineContext,
        audioTimeStretch: ExoPlayerAudioTimeStretch,
    ) : this(context, parentCoroutineContext, audioTimeStretch, null)

    private val backgroundScope: CoroutineScope = CoroutineScope(
        parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job.Key]),
    )

    /**
     * The session handle native facts are reported to. Set by [openImpl] once the open reaches
     * the Ready point; never cleared — the machine invalidates stale handles, and facts
     * reported on an invalidated handle are dropped (spec §5), so no bookkeeping is needed.
     */
    @Volatile
    private var currentSession: PlaybackSessionHandle? = null

    /** Monotonic open counter (main-thread confined), guarding cleanup of superseded opens. */
    private var openEpoch = 0

    /**
     * `true` while the current open has not yet reached the Ready point (spec §3). Written by
     * [openImpl] on the main dispatcher; read by [loadErrorHandlingPolicy] on loader threads
     * to pick the retry schedule per load attempt.
     */
    @Volatile
    private var openingPhase = false

    private val mediaMetadataFeature = ExoPlayerMediaMetadata()

    private val trackSelector = object : DefaultTrackSelector(context) {
        override fun selectTextTrack(
            mappedTrackInfo: MappedTrackInfo,
            rendererFormatSupports: Array<out Array<IntArray>>,
            params: Parameters,
            selectedAudioLanguage: String?
        ): Pair<ExoTrackSelection.Definition, Int>? {
            val preferred = mediaMetadataFeature.subtitleTracks.selected.value
                ?: return super.selectTextTrack(
                    mappedTrackInfo,
                    rendererFormatSupports,
                    params,
                    selectedAudioLanguage,
                )

            infix fun SubtitleTrack.matches(group: TrackGroup): Boolean {
                if (this.internalId == group.id) return true

                if (this.labels.isEmpty()) return false
                for (index in 0 until group.length) {
                    val format = group.getFormat(index)
                    if (format.labels.isEmpty()) {
                        continue
                    }
                    if (this.labels.any { it.value == format.labels.first().value }) {
                        return true
                    }
                }
                return false
            }

            // 备注: 这个实现可能并不好, 他只是恰好能跑
            for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                if (C.TRACK_TYPE_TEXT != mappedTrackInfo.getRendererType(rendererIndex)) continue

                val groups = mappedTrackInfo.getTrackGroups(rendererIndex)
                for (groupIndex in 0 until groups.length) {
                    val trackGroup = groups[groupIndex]
                    if (preferred matches trackGroup) {
                        return Pair(
                            ExoTrackSelection.Definition(
                                trackGroup,
                                IntArray(trackGroup.length) { it }, // 如果选择所有字幕会闪烁
                                TrackSelection.TYPE_UNSET,
                            ),
                            rendererIndex,
                        )
                    }
                }
            }
            return super.selectTextTrack(
                mappedTrackInfo,
                rendererFormatSupports,
                params,
                selectedAudioLanguage,
            )
        }
    }

    /**
     * Persistent listener, registered once at construction. Routes native facts to the current
     * session handle; reports on a stale handle are dropped by the machine automatically.
     */
    private val mediaListener = object : Media3Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Every native intent change is reported as a level (spec §6), including
            // media-session commands; reason codes are not trusted.
            currentSession?.reportTransport(transportSnapshotNow())
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val session = currentSession ?: return
            session.reportTransport(transportSnapshotNow())
            if (playbackState == Media3Player.STATE_ENDED) {
                // media3 also reports STATE_ENDED for a (paused) seek to the end; Ended-on-seek
                // is normalized by the spec (§6), so it is forwarded unconditionally. A stale
                // end-of-media fact inside a seek window is dropped by the machine (§5).
                session.notifyEnded()
            }
            session.notifyProperties(readMediaProperties())
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            currentSession?.reportTransport(transportSnapshotNow())
        }

        override fun onPositionDiscontinuity(
            oldPosition: Media3Player.PositionInfo,
            newPosition: Media3Player.PositionInfo,
            reason: Int,
        ) {
            if (reason != Media3Player.DISCONTINUITY_REASON_SEEK) return
            val session = currentSession ?: return
            // Masked completion (spec §5): media3 fires this at seekTo call time and suppresses
            // stale position/ended facts until the internal seek lands; a post-seek stall flows
            // via reportTransport. Latest-generation stamping closes coalesced seeks.
            session.notifySeekCompleted(
                session.currentSeekGeneration,
                exoPlayer.currentPosition,
                transportSnapshotNow(),
            )
        }

        override fun onPlayerError(error: Media3PlaybackException) {
            currentSession?.notifyError(error.toPlaybackException())
        }

        override fun onTracksChanged(tracks: Tracks) {
            val newSubtitleTracks =
                tracks.groups.asSequence()
                    .filter { it.type == C.TRACK_TYPE_TEXT }
                    .flatMapIndexed { groupIndex: Int, group: Tracks.Group ->
                        group.getSubtitleTracks()
                    }
                    .toList()
            // 只有候选列表真正变化时才更新（Track 有值相等语义，重新创建的对象不会误判为变化）。
            // 变化时按 id 恢复原选择：用户选中的轨道仍存在则保持；用户已关闭字幕则保持关闭；
            // 只有首次出现候选时才默认选择第一轨 (open-ani/animeko#1128)。
            val oldSubtitleTracks = mediaMetadataFeature.subtitleTracks.candidates.value
            if (newSubtitleTracks != oldSubtitleTracks) {
                val previousSelected = mediaMetadataFeature.subtitleTracks.selected.value
                mediaMetadataFeature.subtitleTracks.candidates.value = newSubtitleTracks
                mediaMetadataFeature.subtitleTracks.selected.value = when {
                    oldSubtitleTracks.isEmpty() -> newSubtitleTracks.firstOrNull()
                    previousSelected == null -> null
                    else -> newSubtitleTracks.firstOrNull { it.id == previousSelected.id }
                        ?: newSubtitleTracks.firstOrNull()
                }
            }

            mediaMetadataFeature.audioTracks.candidates.value =
                tracks.groups.asSequence()
                    .filter { it.type == C.TRACK_TYPE_AUDIO }
                    .flatMapIndexed { groupIndex: Int, group: Tracks.Group ->
                        group.getAudioTracks()
                    }
                    .toList()

            // Duration typically becomes known once tracks are parsed (v1 defect E6: do not
            // require both video AND audio formats to publish properties).
            currentSession?.notifyProperties(readMediaProperties())
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            currentSession?.notifyProperties(readMediaProperties())
        }
    }

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .apply {
            setTrackSelector(trackSelector)
            if (audioTimeStretch == ExoPlayerAudioTimeStretch.HighQualityWsola) {
                val renderersFactory = WsolaRenderersFactory(context)
                setRenderersFactory(renderersFactory)
            }
        }
        .build()
        .apply {
            addListener(mediaListener)
        }

    override val impl: ExoPlayer get() = exoPlayer

    private val buffering = ExoPlayerBuffering(state)
    private val videoAspectRatio = ExoPlayerVideoAspectRatio()
    private val framePreview = ExoFramePreview { mediaData.value }

    override val features: PlayerFeatures = buildPlayerFeatures {
        add(PlaybackSpeed, machinePlaybackSpeed())
        add(Buffering, buffering)
        add(MediaMetadata, mediaMetadataFeature)
        add(VideoAspectRatio, videoAspectRatio)
        add(FramePreview.Key, framePreview)
    }

    init {
        backgroundScope.launch(Dispatchers.Main) {
            // 10 tps position poll: routed through the session handle so the machine remains
            // the single flow writer, and stale-session/seek-in-flight reports are dropped.
            while (currentCoroutineContext().isActive) {
                val session = currentSession
                if (session != null && session.isValid) {
                    session.notifyPosition(exoPlayer.currentPosition)
                    buffering.bufferedPercentage.value = exoPlayer.bufferedPercentage
                }
                delay(0.1.seconds)
            }
        }
        backgroundScope.launch {
            // Tear down the preview decoder when the media changes or playback stops,
            // so it never outlives the media data it reads from.
            mediaData.collect {
                framePreview.onMediaDataChanged(it)
            }
        }
        backgroundScope.launch(Dispatchers.Main) {
            mediaMetadataFeature.subtitleTracks.selected.collect {
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().apply {
                    setPreferredTextLanguage(it?.internalId) // dummy value to trigger a select, we have custom selector
                    setTrackTypeDisabled(C.TRACK_TYPE_TEXT, it == null) // disable subtitle track
                }.build()
            }
        }
    }

    // region SPI

    override suspend fun openImpl(
        data: MediaData,
        session: PlaybackSessionHandle,
        playWhenReady: Boolean,
        startPositionMillis: Long,
    ): OpenResult {
        val epoch = ++openEpoch
        openingPhase = true
        var sessionInput: SeekableInput? = null
        var sessionInputAwaitJob: Job? = null
        try {
            val prepared = buildMediaSource(data)
            sessionInput = prepared.sessionInput
            sessionInputAwaitJob = prepared.sessionInputAwaitJob
            val source = mediaSourceInterceptor?.invoke(prepared.mediaSource, data) ?: prepared.mediaSource

            // Ready point (spec §3): the first onTracksChanged with non-empty tracks, which
            // fires after container parse — provably after real I/O, and never before
            // onPlayerError for a failed open. The initial placeholder timeline refresh
            // reports empty tracks and is NOT sufficient.
            val readySignal = CompletableDeferred<Unit>()
            val openListener = object : Media3Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    if (!tracks.isEmpty) {
                        readySignal.complete(Unit)
                    }
                }

                override fun onPlayerError(error: Media3PlaybackException) {
                    readySignal.completeExceptionally(error.toPlaybackException())
                }
            }
            exoPlayer.addListener(openListener)
            try {
                exoPlayer.setMediaSource(source, startPositionMillis)
                // Apply the pending intent natively before completing (spec §5 open handoff).
                exoPlayer.playWhenReady = playWhenReady
                exoPlayer.prepare()
                readySignal.await()
            } finally {
                exoPlayer.removeListener(openListener)
            }
            // Ready point reached: restore media3's default retry schedule for this session.
            openingPhase = false

            currentSession = session
            val nativeState = exoPlayer.playbackState
            // atEnd (spec §5): STATE_ENDED covers zero-length media, but the Ready point fires
            // while still STATE_BUFFERING, so a start-at/beyond-end open is detected from the
            // requested start position against the now-known duration (STATE_ENDED only arrives
            // asynchronously later).
            val durationMillis = exoPlayer.duration
            val awaitJob = sessionInputAwaitJob
            return OpenResult(
                sessionResources = sessionInput?.let { input ->
                    AutoCloseable {
                        // Cancel before closing: unblocks a loader read still waiting in
                        // the await context.
                        awaitJob?.cancel()
                        input.close()
                    }
                },
                initialSnapshot = TransportSnapshot(
                    nativePlayWhenReady = exoPlayer.playWhenReady,
                    isStalled = nativeState == Media3Player.STATE_BUFFERING,
                ),
                atEnd = nativeState == Media3Player.STATE_ENDED ||
                    (durationMillis != C.TIME_UNSET && durationMillis > 0 && startPositionMillis >= durationMillis),
                initialProperties = readMediaProperties(),
            )
        } catch (e: Throwable) {
            // Open failed, was superseded, or was cancelled. We are back on the main dispatcher
            // here (cancellation resumes in the caller's context), so native calls are legal.
            if (openEpoch == epoch) {
                // Only unload if no newer open owns the native player already (a newer open
                // owns openingPhase too, so it is only cleared under the same guard).
                openingPhase = false
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
            sessionInputAwaitJob?.cancel()
            sessionInput?.let { input ->
                backgroundScope.launch(NonCancellable + Dispatchers.IO) {
                    runCatching { input.close() }
                }
            }
            throw e
        }
    }

    override fun playImpl() {
        exoPlayer.playWhenReady = true
        // Read-after-command (spec §5): media3 getters are masked and reflect the command
        // immediately, so this observation is synchronous and always fresh.
        currentSession?.reportTransport(transportSnapshotNow())
    }

    override fun pauseImpl() {
        exoPlayer.playWhenReady = false
        currentSession?.reportTransport(transportSnapshotNow())
    }

    override fun seekImpl(positionMillis: Long, seekGeneration: Int) {
        // Completion is reported by onPositionDiscontinuity(DISCONTINUITY_REASON_SEEK), which
        // media3 fires for every seekTo call regardless of state (masked), so every issued
        // seek yields exactly one completion and the gate cannot wedge (spec §5).
        exoPlayer.seekTo(positionMillis)
    }

    override fun setRateImpl(rate: Float) {
        exoPlayer.setPlaybackSpeed(rate)
    }

    override fun stopImpl() {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
    }

    override fun closeImpl() {
        // Cancel the poll/collector scope BEFORE releasing the player, so no poll iteration
        // can touch a released ExoPlayer (v1 defect E2).
        backgroundScope.cancel()
        exoPlayer.removeListener(mediaListener)
        exoPlayer.stop()
        exoPlayer.release()
        backgroundScope.launch(NonCancellable + Dispatchers.IO) {
            framePreview.closeSuspending()
        }
    }
    // endregion

    // region helpers

    private class PreparedSource(
        val mediaSource: MediaSource,
        /** A [SeekableInput] opened for this session, to be closed with it; `null` if none. */
        val sessionInput: SeekableInput?,
        /** Await-context job of [sessionInput]'s blocking reads, cancelled with the session. */
        val sessionInputAwaitJob: Job? = null,
    )

    /**
     * Phase-aware retry policy (spec §3): until the current open reaches the Ready point, load
     * errors surface after a single attempt with no backoff, so a bad source fails
     * `setMediaData` promptly. After Ready, media3's default schedule (3 attempts + backoff)
     * applies, so a transient mid-playback network blip is retried instead of surfacing as a
     * fatal [Media3Player.Listener.onPlayerError]. [LoadErrorHandlingPolicy.getMinimumLoadableRetryCount]
     * is consulted per load attempt, so the [openingPhase] flip is picked up without rebuilding
     * the media source. The fatal-error mapping ([C.TIME_UNSET] delays) is preserved in both phases.
     */
    private val loadErrorHandlingPolicy = object : LoadErrorHandlingPolicy {
        private val delegate = DefaultLoadErrorHandlingPolicy()

        override fun getFallbackSelectionFor(
            fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
            loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
        ): LoadErrorHandlingPolicy.FallbackSelection? =
            delegate.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)

        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val delayMs = delegate.getRetryDelayMsFor(loadErrorInfo)
            // C.TIME_UNSET marks a non-retriable (fatal) error; only retriable delays shrink.
            return if (openingPhase && delayMs != C.TIME_UNSET) 0L else delayMs
        }

        override fun getMinimumLoadableRetryCount(dataType: Int): Int =
            if (openingPhase) 1 else delegate.getMinimumLoadableRetryCount(dataType)
    }

    private suspend fun buildMediaSource(data: MediaData): PreparedSource = when (data) {
        is UriMediaData -> {
            val headers = data.headers
            val item = MediaItem.Builder().apply {
                setUri(data.uri)
                setSubtitleConfigurations(
                    data.extraFiles.subtitles.map {
                        MediaItem.SubtitleConfiguration.Builder(
                            Uri.parse(it.uri),
                        ).apply {
                            it.label?.let { label -> setLabel(label) }
                            it.mimeType?.let { mimeType -> setMimeType(mimeType) }
                            it.language?.let { language -> setLanguage(language) }
                        }.build()
                    },
                )
            }.build()
            val factory = DefaultMediaSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setUserAgent(
                        headers["User-Agent"]
                            ?: """Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3""",
                    )
                    .setDefaultRequestProperties(headers)
                    .setConnectTimeoutMs(30_000),
            ).setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            PreparedSource(factory.createMediaSource(item), sessionInput = null)
        }

        is SeekableInputMediaData -> {
            if (data.uri.startsWith("file://")) {
                val factory = DefaultMediaSourceFactory {
                    FileDataSource()
                }.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                PreparedSource(factory.createMediaSource(MediaItem.fromUri(data.uri)), sessionInput = null)
            } else {
                // The input's reads run on ExoPlayer loader threads for the whole session,
                // and a read that must wait for data (e.g. a torrent input awaiting an
                // undownloaded piece) blocks inside the await context passed here. This open
                // coroutine's job (and the withContext job below) completes right after the
                // open, so neither may be that context: every later wait would fail
                // instantly with "Parent job is Completed". Hand the input a
                // session-lifetime job instead.
                val awaitJob = SupervisorJob(backgroundScope.coroutineContext[Job.Key])
                val input = try {
                    withContext(Dispatchers.IO) {
                        data.createInput(Dispatchers.IO + awaitJob)
                    }
                } catch (t: Throwable) {
                    awaitJob.cancel()
                    throw t
                }
                val factory = ProgressiveMediaSource.Factory {
                    SeekableInputDataSource(data, input)
                }.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                PreparedSource(
                    factory.createMediaSource(MediaItem.fromUri(data.uri)),
                    sessionInput = input,
                    sessionInputAwaitJob = awaitJob,
                )
            }
        }
    }

    @MainThread
    private fun transportSnapshotNow(): TransportSnapshot = TransportSnapshot(
        nativePlayWhenReady = exoPlayer.playWhenReady,
        isStalled = exoPlayer.playbackState == Media3Player.STATE_BUFFERING,
    )

    /**
     * Reads the current media properties from the player. Duration is published whenever known,
     * independent of which formats are selected (v1 defect E6); media3's `C.TIME_UNSET`
     * sentinel maps to `null`, never a negative value (v1 defect E8).
     */
    @MainThread
    private fun readMediaProperties(): MediaProperties {
        val videoSize = exoPlayer.videoSize
        return MediaProperties(
            title = exoPlayer.mediaMetadata.title?.toString(),
            durationMillis = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it >= 0 },
            videoWidth = (videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt().takeIf { it > 0 },
            videoHeight = videoSize.height.takeIf { it > 0 },
        )
    }

    private fun Tracks.Group.getSubtitleTracks() = sequence {
        repeat(length) { index ->
            val format = getTrackFormat(index)
            val firstLabel = format.labels.firstNotNullOfOrNull { it.value }
            format.metadata
            this.yield(
                SubtitleTrack(
                    "${mediaTrackGroup.id}-$index",
                    mediaTrackGroup.id,
                    firstLabel ?: mediaTrackGroup.id,
                    format.labels.map { TrackLabel(it.language, it.value) },
                ),
            )
        }
    }

    private fun Tracks.Group.getAudioTracks() = sequence {
        repeat(length) { index ->
            val format = getTrackFormat(index)
            val firstLabel = format.labels.firstNotNullOfOrNull { it.value }
            format.metadata
            this.yield(
                AudioTrack(
                    "${mediaTrackGroup.id}-$index",
                    mediaTrackGroup.id,
                    firstLabel ?: mediaTrackGroup.id,
                    format.labels.map { TrackLabel(it.language, it.value) },
                ),
            )
        }
    }
    // endregion
}

@kotlin.OptIn(InternalForInheritanceMediampApi::class)
internal class ExoPlayerMediaMetadata : MediaMetadata {
    override val subtitleTracks: MutableTrackGroup<SubtitleTrack> = MutableTrackGroup()
    override val audioTracks: MutableTrackGroup<AudioTrack> = MutableTrackGroup()

    override val chapters: StateFlow<List<Chapter>> = MutableStateFlow(listOf())
}

@kotlin.OptIn(ExperimentalMediampApi::class, InternalForInheritanceMediampApi::class)
internal class ExoPlayerBuffering(
    state: StateFlow<PlayerState>,
) : Buffering {
    @Deprecated(
        "Buffering is part of the core state now. Use player.state.map { it.isBuffering }.",
        ReplaceWith("player.state.map { it.isBuffering }"),
    )
    override val isBuffering: Flow<Boolean> = state.map { it.isBuffering }
    override val bufferedPercentage: MutableStateFlow<Int> = MutableStateFlow(0)
}

@kotlin.OptIn(InternalForInheritanceMediampApi::class)
internal class ExoPlayerVideoAspectRatio : VideoAspectRatio {
    override val mode: MutableStateFlow<AspectRatioMode> = MutableStateFlow(AspectRatioMode.FIT)

    override fun setMode(mode: AspectRatioMode) {
        this.mode.value = mode
    }
}
