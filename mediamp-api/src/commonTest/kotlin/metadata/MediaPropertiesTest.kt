/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MediaPropertiesTest {
    @Test
    fun `video dimensions participate in value equality`() {
        val properties = MediaProperties("title", 1_000, 1920, 1080)

        assertEquals(properties, MediaProperties("title", 1_000, 1920, 1080))
        assertEquals(properties.hashCode(), MediaProperties("title", 1_000, 1920, 1080).hashCode())
        assertNotEquals(properties, properties.copy(videoWidth = 1280))
        assertNotEquals(properties, properties.copy(videoHeight = 720))
    }

    @Test
    fun `copy preserves video dimensions by default`() {
        val properties = MediaProperties(videoWidth = 1920, videoHeight = 1080)

        val copied = properties.copy(title = "title")

        assertEquals(1920, copied.videoWidth)
        assertEquals(1080, copied.videoHeight)
    }
}
