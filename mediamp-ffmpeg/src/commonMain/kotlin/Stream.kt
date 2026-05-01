/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

/**
 * A media stream within a container.
 *
 * This is a Kotlin wrapper around FFmpeg's `AVStream`, exposing essential
 * properties such as index, time base, and codec parameters.
 */
public expect class Stream {
    /**
     * The index of this stream in the parent container.
     */
    public val index: Int

    /**
     * The time base of this stream.
     */
    public val timeBase: Rational

    /**
     * The codec type (audio, video, subtitle, etc.).
     */
    public val codecType: Int

    /**
     * The codec ID.
     */
    public val codecId: Int
}
