/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvNetworkUriTest {
    @Test
    fun `http and https are network`() {
        assertTrue(isNetworkUri("http://example.com/a.mp4"))
        assertTrue(isNetworkUri("https://example.com/a.m3u8?token=1"))
        assertTrue(isNetworkUri("HTTPS://EXAMPLE.COM/A.MP4"))
    }

    @Test
    fun `other streaming schemes are network`() {
        assertTrue(isNetworkUri("rtmp://host/live"))
        assertTrue(isNetworkUri("rtsp://host/stream"))
        assertTrue(isNetworkUri("ftp://host/file.mkv"))
    }

    @Test
    fun `file scheme is local`() {
        assertFalse(isNetworkUri("file:///tmp/a.mp4"))
        assertFalse(isNetworkUri("FILE:///tmp/a.mp4"))
    }

    @Test
    fun `plain paths are local`() {
        assertFalse(isNetworkUri("/tmp/a.mp4"))
        assertFalse(isNetworkUri("relative/a.mp4"))
        assertFalse(isNetworkUri("C:\\Videos\\a.mp4"))
        assertFalse(isNetworkUri("D:/Videos/a.mp4"))
    }

    @Test
    fun `paths containing a colon later are local`() {
        assertFalse(isNetworkUri("/tmp/dir with:colon/a.mp4"))
    }
}
