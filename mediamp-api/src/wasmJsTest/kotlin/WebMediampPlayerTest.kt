/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import kotlinx.browser.document
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.Subtitle
import org.openani.mediamp.source.UriMediaData
import org.w3c.dom.HTMLTrackElement
import org.w3c.dom.HTMLVideoElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebMediampPlayerTest {
    @Test
    fun `factory creates web player`(): TestResult = runTest {
        val player = MediampPlayer(Unit)
        assertIs<WebMediampPlayer>(player)
        assertEquals(PlaybackState.CREATED, player.playbackState.value)
    }

    @Test
    fun `set uri media installs video source and subtitle tracks`(): TestResult = runTest {
        val video = document.createElement("video") as HTMLVideoElement
        val player = WebMediampPlayer(video)

        player.setMediaData(
            UriMediaData(
                uri = "https://example.invalid/video.mp4",
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

        assertEquals(PlaybackState.READY, player.playbackState.value)
        assertTrue(video.src.endsWith("/video.mp4"))
        assertEquals(1, video.children.length)
        val track = video.children.item(0)
        assertIs<HTMLTrackElement>(track)
        assertEquals("https://example.invalid/subtitle.vtt", track.src)
        assertEquals("en", track.srclang)
        assertEquals("English", track.label)
    }

    @Test
    fun `web feature controls mutate native video element`(): TestResult = runTest {
        val video = document.createElement("video") as HTMLVideoElement
        val player = WebMediampPlayer(video)

        val speed = assertNotNull(player.features[PlaybackSpeed])
        speed.set(1.5f)
        assertEquals(1.5f, speed.value)
        assertEquals(1.5, video.playbackRate)

        val audio = assertNotNull(player.features[AudioLevelController])
        audio.setVolume(0.25f)
        audio.setMute(true)
        assertEquals(0.25f, audio.volume.value)
        assertEquals(0.25, video.volume)
        assertTrue(video.muted)

        val aspectRatio = assertNotNull(player.features[VideoAspectRatio])
        aspectRatio.setMode(AspectRatioMode.CROP)
        assertEquals(AspectRatioMode.CROP, aspectRatio.mode.value)
        assertEquals("cover", video.style.objectFit)
    }

    @Test
    fun `stop and close reset native video element state`(): TestResult = runTest {
        val video = document.createElement("video") as HTMLVideoElement
        val player = WebMediampPlayer(video)
        player.setMediaData(UriMediaData("https://example.invalid/video.mp4"))

        player.stopPlayback()
        assertEquals(PlaybackState.FINISHED, player.playbackState.value)
        assertTrue(video.src.isBlank() || !video.hasAttribute("src"))

        player.close()
        assertEquals(PlaybackState.DESTROYED, player.playbackState.value)
    }
}
