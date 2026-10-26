/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(InternalMediampApi::class)

package org.openani.mediamp.mpv

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.mpv.utils.MpvTestMedia
import org.openani.mediamp.mpv.utils.RangeFileServer
import org.openani.mediamp.playUri
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Playback speed against a real libmpv: the machine stores a rate set while the player is not
 * playing and must push it to mpv's `speed` property once playback resumes, whatever the value.
 *
 * Skipped when the native runtime or ffmpeg (for test clip generation) is unavailable.
 */
class MpvPlaybackSpeedTest {

    private fun prepareOrSkip(): Boolean = MpvTestMedia.prepareOrSkip(TAG)
    private fun skip(reason: String): Boolean = MpvTestMedia.skip(TAG, reason)
    private fun generateClip(): File? = MpvTestMedia.generateClip(CLIP_SECONDS)

    private fun withPlayer(block: suspend (player: MpvMediampPlayer, handle: MPVHandle, speed: PlaybackSpeed) -> Unit) {
        // A dedicated single-thread dispatcher stands in for the UI thread. It must own exactly
        // one physical thread, since the machine checks command threads by identity.
        val mainDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mpv-speed-test-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        try {
            runBlocking(mainDispatcher) {
                val player = MpvMediampPlayer(Any(), coroutineContext, mainDispatcher = mainDispatcher)
                try {
                    check(player.createRenderContext()) { "createRenderContext failed" }
                    val handle = player.impl as MPVHandle
                    handle.setPropertyString("ao", "null")
                    val speed = assertNotNull(player.features[PlaybackSpeed], "mpv player must expose PlaybackSpeed")
                    withTimeout(TEST_TIMEOUT_MILLIS) { block(player, handle, speed) }
                } finally {
                    player.close()
                }
            }
        } finally {
            mainDispatcher.close()
        }
    }

    private fun MPVHandle.nativeSpeed(): Double = getPropertyDouble("speed")

    /**
     * open-ani/animeko#3368 and #3664: long-press acceleration released while the video was
     * buffering kept playing fast. Reproduced here with a bandwidth cap that sustains 1x but
     * starves 2.5x, so mpv stalls (`paused-for-cache`) during the fast phase.
     */
    @Test
    fun `rate lowered while stalled reaches mpv when playback resumes`() {
        if (!prepareOrSkip()) return
        val clip = generateClip() ?: run { skip("ffmpeg unavailable or clip generation failed"); return }
        RangeFileServer(clip, bytesPerSecond = sustainsNormalSpeedOnly(clip)).use { server ->
            withPlayer { player, handle, speed ->
                player.playUri(server.url)
                assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
                withTimeout(15_000) { player.state.first { it.isPlaying } }
                withTimeout(15_000) { player.currentPositionMillis.first { it >= 500 } }
                assertEquals(1.0, handle.nativeSpeed())

                speed.set(FAST_RATE)
                assertEquals(FAST_RATE.toDouble(), handle.nativeSpeed(), "applied natively while playing")

                // The fast phase outruns the download and mpv stalls while the intent stays play.
                withTimeout(STALL_TIMEOUT_MILLIS) { player.state.first { it.playWhenReady && it.isBuffering } }

                // Long-press released during the stall: stored only, mpv still at the fast rate.
                speed.set(1f)
                assertEquals(1f, speed.value)
                assertEquals(FAST_RATE.toDouble(), handle.nativeSpeed(), "not playing: stored only (spec section 6)")

                // Lift the cap so the stall ends regardless of how much the fast phase consumed.
                server.bytesPerSecond = null
                withTimeout(STALL_TIMEOUT_MILLIS) { player.state.first { it.isPlaying } }
                assertEquals(1.0, handle.nativeSpeed(), "the stored 1x must reach mpv on resume")

                // Playback really continues at 1x: the clock advances but does not run away.
                val before = player.currentPositionMillis.value
                withTimeout(15_000) { player.currentPositionMillis.first { it >= before + 1_000 } }
                assertEquals(1.0, handle.nativeSpeed())
            }
        }
    }

    @Test
    fun `rate lowered while paused reaches mpv on play`() {
        if (!prepareOrSkip()) return
        val clip = generateClip() ?: run { skip("ffmpeg unavailable or clip generation failed"); return }
        withPlayer { player, handle, speed ->
            player.playUri(clip.absolutePath)
            withTimeout(15_000) { player.state.first { it.isPlaying } }
            speed.set(FAST_RATE)
            assertEquals(FAST_RATE.toDouble(), handle.nativeSpeed())

            player.pause()
            assertTrue(handle.getPropertyBoolean("pause"))
            speed.set(1f)
            assertEquals(FAST_RATE.toDouble(), handle.nativeSpeed(), "paused: stored only (spec section 6)")

            player.play()
            withTimeout(15_000) { player.state.first { it.isPlaying } }
            assertEquals(1.0, handle.nativeSpeed(), "the stored 1x must reach mpv on play")
        }
    }

    /**
     * Bandwidth a little above the clip's average bitrate: 1x playback never starves, while
     * [FAST_RATE] consumes data faster than it arrives and stalls within a few seconds.
     */
    private fun sustainsNormalSpeedOnly(clip: File): Long = clip.length() * NORMAL_SPEED_HEADROOM_PERCENT / 100 / CLIP_SECONDS

    private companion object {
        const val TAG = "MpvPlaybackSpeedTest"
        const val CLIP_SECONDS = 60
        const val FAST_RATE = 2.5f
        const val NORMAL_SPEED_HEADROOM_PERCENT = 130L
        const val STALL_TIMEOUT_MILLIS = 60_000L
        const val TEST_TIMEOUT_MILLIS = 180_000L
    }
}
