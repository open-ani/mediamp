/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.Subtitle
import org.openani.mediamp.source.UriMediaData
import org.w3c.dom.HTMLTrackElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import kotlin.coroutines.CoroutineContext
import kotlin.io.encoding.Base64
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WebMediampPlayerTest {

    // region test harness

    /**
     * Runs [block] on [Dispatchers.Main]: playback commands must be issued on the machine's
     * main dispatcher thread, and suspending there lets the browser event loop deliver real
     * DOM events while the test waits.
     */
    private fun runPlayerTest(block: suspend CoroutineScope.() -> Unit): TestResult =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Main) { block() }
        }

    /** Polls [predicate] with real delays (virtual time does not drive the DOM). */
    private suspend fun waitUntil(message: String, timeoutMillis: Long = 10_000, predicate: () -> Boolean) {
        var waited = 0L
        while (!predicate()) {
            if (waited >= timeoutMillis) throw AssertionError("Timed out waiting for: $message")
            delay(20)
            waited += 20
        }
    }

    private fun newVideoElement(): HTMLVideoElement = document.createElement("video") as HTMLVideoElement

    /**
     * A tiny silent PCM WAV as a data URI — real, decodable media the browser can open and
     * play to the end without any network access.
     */
    private fun silentWavDataUri(durationSeconds: Double): String {
        val sampleRate = 8000
        val dataSize = (durationSeconds * sampleRate).toInt() // 8-bit mono
        val bytes = ByteArray(44 + dataSize)
        fun writeString(offset: Int, s: String) {
            for (i in s.indices) bytes[offset + i] = s[i].code.toByte()
        }

        fun writeIntLe(offset: Int, value: Int) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
            bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        fun writeShortLe(offset: Int, value: Int) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        writeString(0, "RIFF"); writeIntLe(4, 36 + dataSize); writeString(8, "WAVE")
        writeString(12, "fmt "); writeIntLe(16, 16); writeShortLe(20, 1); writeShortLe(22, 1)
        writeIntLe(24, sampleRate); writeIntLe(28, sampleRate); writeShortLe(32, 1); writeShortLe(34, 8)
        writeString(36, "data"); writeIntLe(40, dataSize)
        for (i in 0 until dataSize) bytes[44 + i] = 0x80.toByte() // 8-bit PCM midpoint = silence
        return "data:audio/wav;base64," + Base64.encode(bytes)
    }

    /** Collects [MediampPlayer.events] for the duration of a test. Cancel [job] when done. */
    private class EventLog(val received: MutableList<PlaybackEvent>, val job: Job)

    private suspend fun CoroutineScope.collectEvents(player: MediampPlayer): EventLog {
        val received = mutableListOf<PlaybackEvent>()
        val job = launch { player.events.collect { received += it } }
        delay(1) // let the collector subscribe before commands are issued
        return EventLog(received, job)
    }
    // endregion

    @Test
    fun `factory creates web player in initial state`(): TestResult = runPlayerTest {
        val player = MediampPlayer(Unit)
        assertIs<WebMediampPlayer>(player)
        assertEquals(PlayerState.Initial, player.state.value)
        assertNull(player.mediaData.value)
        assertEquals(0L, player.currentPositionMillis.value)
        player.close()
    }

    @Test
    fun `registered factory takes precedence over built-in`(): TestResult = runPlayerTest {
        val custom = DelegatingWebFactory()
        MediampPlayerFactoryLoader.register(custom)
        assertSame(custom, MediampPlayerFactoryLoader.first())
        // The registration is still functional.
        val player = MediampPlayer(Unit)
        assertIs<WebMediampPlayer>(player)
        player.close()
    }

    @Test
    fun `setMediaData with unplayable source throws and enters Error`(): TestResult = runPlayerTest {
        val video = newVideoElement()
        val player = WebMediampPlayer(video)

        val thrown = assertFailsWith<PlaybackException> {
            player.setMediaData(UriMediaData("data:video/mp4;base64,AAAAAA=="))
        }

        val status = player.state.value.mediaStatus
        assertIs<MediaStatus.Error>(status)
        assertSame(thrown, status.error)
        assertNull(player.mediaData.value)
        assertFalse(player.state.value.playWhenReady)

        // Error is dismissible back to Idle.
        player.stopPlayback()
        assertEquals(MediaStatus.Idle, player.state.value.mediaStatus)
        player.close()
    }

    @Test
    fun `open applies start position and play pause flip intent synchronously`(): TestResult = runPlayerTest {
        val video = newVideoElement()
        video.muted = true // muted autoplay is always allowed
        val player = WebMediampPlayer(video)

        player.setMediaData(
            UriMediaData(silentWavDataUri(durationSeconds = 2.0)),
            playWhenReady = false,
            startPositionMillis = 500L,
        )

        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
        assertFalse(player.state.value.playWhenReady)
        assertNotNull(player.mediaData.value)
        val duration = assertNotNull(player.mediaProperties.value?.durationMillis)
        assertTrue(duration in 1900..2100, "duration=$duration")
        assertTrue(player.currentPositionMillis.value in 400..600, "position=${player.currentPositionMillis.value}")

        player.play()
        assertTrue(player.state.value.playWhenReady) // synchronous intent flip
        waitUntil("clock advancing after play()") { player.state.value.isPlaying }
        assertFalse(video.paused)

        player.pause()
        assertFalse(player.state.value.playWhenReady) // synchronous intent flip
        waitUntil("element paused after pause()") { video.paused }
        player.close()
    }

    @Test
    fun `set uri media installs video source and subtitle tracks`(): TestResult = runPlayerTest {
        val video = newVideoElement()
        val player = WebMediampPlayer(video)

        player.setMediaData(
            UriMediaData(
                uri = silentWavDataUri(durationSeconds = 1.0),
                extraFiles = MediaExtraFiles(
                    subtitles = listOf(
                        Subtitle(
                            uri = "https://example.invalid/subtitle.vtt",
                            mimeType = "text/vtt",
                            language = "en",
                            label = "English",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
        assertEquals(1, video.children.length)
        val track = video.children.item(0)
        assertIs<HTMLTrackElement>(track)
        assertEquals("https://example.invalid/subtitle.vtt", track.src)
        assertEquals("en", track.srclang)
        assertEquals("English", track.label)
        player.close()
    }

    @Test
    fun `natural end enters Ended and emits MediaEnded`(): TestResult = runPlayerTest {
        val video = newVideoElement()
        video.muted = true
        val player = WebMediampPlayer(video)
        val events = collectEvents(player)

        player.setMediaData(UriMediaData(silentWavDataUri(durationSeconds = 0.2)), playWhenReady = true)
        assertTrue(player.state.value.playWhenReady)

        waitUntil("Ended status after playthrough") { player.state.value.mediaStatus == MediaStatus.Ended }
        assertFalse(player.state.value.playWhenReady) // I2: Ended pins intent to false
        assertFalse(player.state.value.isBuffering)
        assertNotNull(player.mediaData.value) // media retained at Ended

        val ended = assertNotNull(events.received.filterIsInstance<PlaybackEvent.MediaEnded>().firstOrNull())
        assertNotNull(ended.durationMillis)
        assertTrue(ended.finalPositionMillis > 0)
        // The browser's mandated pause-before-ended must not surface as an external change.
        assertTrue(events.received.filterIsInstance<PlaybackEvent.ExternalPlayWhenReadyChanged>().isEmpty())

        events.job.cancel()
        player.close()
    }

    @Test
    fun `native element pause is adopted as external intent change`(): TestResult = runPlayerTest {
        val video = newVideoElement()
        video.muted = true
        val player = WebMediampPlayer(video)
        val events = collectEvents(player)

        player.setMediaData(UriMediaData(silentWavDataUri(durationSeconds = 5.0)), playWhenReady = true)
        waitUntil("clock advancing") { player.state.value.isPlaying }

        video.pause() // an external pause, e.g. Global Media Controls / PiP

        waitUntil("external pause adopted") { !player.state.value.playWhenReady }
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
        waitUntil("ExternalPlayWhenReadyChanged(false) published") {
            events.received.filterIsInstance<PlaybackEvent.ExternalPlayWhenReadyChanged>()
                .any { !it.value }
        }

        events.job.cancel()
        player.close()
    }

    @Test
    fun `synthetic waiting and canplay events drive isBuffering`(): TestResult = runPlayerTest {
        val video = newVideoElement()
        val player = WebMediampPlayer(video)

        player.setMediaData(UriMediaData(silentWavDataUri(durationSeconds = 2.0)))
        waitUntil("initial prefetch complete") { !player.state.value.isBuffering }

        video.dispatchEvent(Event("waiting"))
        waitUntil("stall reported from waiting event") { player.state.value.isBuffering }
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus) // I1

        video.dispatchEvent(Event("canplay")) // stall re-evaluated from readyState at this edge
        waitUntil("stall cleared at canplay edge") { !player.state.value.isBuffering }
        player.close()
    }

    @Test
    fun `synthetic ended event enters Ended`(): TestResult = runPlayerTest {
        val video = newVideoElement()
        val player = WebMediampPlayer(video)
        val events = collectEvents(player)

        player.setMediaData(UriMediaData(silentWavDataUri(durationSeconds = 2.0)))
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)

        video.dispatchEvent(Event("ended"))

        waitUntil("Ended status from synthetic ended event") {
            player.state.value.mediaStatus == MediaStatus.Ended
        }
        waitUntil("MediaEnded published") {
            events.received.filterIsInstance<PlaybackEvent.MediaEnded>().isNotEmpty()
        }

        events.job.cancel()
        player.close()
    }

    @Test
    fun `seekTo updates position optimistically and completes natively`(): TestResult = runPlayerTest {
        val video = newVideoElement()
        val player = WebMediampPlayer(video)
        val events = collectEvents(player)

        player.setMediaData(UriMediaData(silentWavDataUri(durationSeconds = 2.0)))

        player.seekTo(1000L)
        assertEquals(1000L, player.currentPositionMillis.value) // optimistic emission

        waitUntil("seek completion") {
            events.received.filterIsInstance<PlaybackEvent.SeekCompleted>().isNotEmpty()
        }
        val completed = events.received.filterIsInstance<PlaybackEvent.SeekCompleted>().first()
        assertTrue(completed.positionMillis in 900..1100, "landed=${completed.positionMillis}")
        assertFalse(player.state.value.playWhenReady) // paused seek stays paused

        events.job.cancel()
        player.close()
    }

    @Test
    fun `stopPlayback returns to Idle and unloads the element`(): TestResult = runPlayerTest {
        val video = newVideoElement()
        val player = WebMediampPlayer(video)

        player.setMediaData(UriMediaData(silentWavDataUri(durationSeconds = 1.0)))
        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)

        player.stopPlayback()

        assertEquals(PlayerState.Initial, player.state.value)
        assertNull(player.mediaData.value)
        assertNull(player.mediaProperties.value)
        assertEquals(0L, player.currentPositionMillis.value)
        assertFalse(video.hasAttribute("src"))
        player.close()
    }

    @Test
    fun `close releases the player permanently`(): TestResult = runPlayerTest {
        val video = newVideoElement()
        val player = WebMediampPlayer(video)
        player.setMediaData(UriMediaData(silentWavDataUri(durationSeconds = 1.0)))

        player.close()

        assertEquals(MediaStatus.Released, player.state.value.mediaStatus)
        assertFalse(video.hasAttribute("src"))
        // Commands are no-ops after close.
        player.play()
        assertEquals(MediaStatus.Released, player.state.value.mediaStatus)
        assertFalse(player.state.value.playWhenReady)
    }

    @Test
    fun `web feature controls mutate native video element`(): TestResult = runPlayerTest {
        val video = newVideoElement()
        val player = WebMediampPlayer(video)

        val speed = assertNotNull(player.features[org.openani.mediamp.features.PlaybackSpeed])
        speed.set(1.5f)
        assertEquals(1.5f, speed.value)

        val audio = assertNotNull(player.features[org.openani.mediamp.features.AudioLevelController])
        audio.setVolume(0.25f)
        audio.setMute(true)
        assertEquals(0.25f, audio.volume.value)
        assertEquals(0.25, video.volume)
        assertTrue(video.muted)

        val aspectRatio = assertNotNull(player.features[org.openani.mediamp.features.VideoAspectRatio])
        aspectRatio.setMode(org.openani.mediamp.features.AspectRatioMode.CROP)
        assertEquals(org.openani.mediamp.features.AspectRatioMode.CROP, aspectRatio.mode.value)
        assertEquals("cover", video.style.objectFit)
        player.close()
    }
}

private class DelegatingWebFactory : MediampPlayerFactory<WebMediampPlayer> {
    override val forClass: KClass<WebMediampPlayer> = WebMediampPlayer::class

    override fun create(context: Any, parentCoroutineContext: CoroutineContext): WebMediampPlayer =
        WebMediampPlayer.Factory.create(context, parentCoroutineContext)
}
