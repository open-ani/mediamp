/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.metadata

import org.openani.mediamp.InternalMediampApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Class containing metadata properties of a media, for example, title and total duration.
 *
 * Can be obtained from [org.openani.mediamp.MediampPlayer.mediaProperties].
 */
public sealed interface MediaProperties {
    /**
     * Title of the media (video).
     *
     * This value might be `null` if the title is unknown.
     */
    public val title: String?

    /**
     * Total duration of the media in milliseconds.
     *
     * This value might be `-1` if the duration is unknown.
     */
    public val durationMillis: Long
}

/**
 * Total duration of the media, in Kotlin [Duration].
 */
public inline val MediaProperties.duration: Duration get() = durationMillis.milliseconds

@InternalMediampApi
public class MediaPropertiesImpl @InternalMediampApi public constructor(
    override val title: String?,
    override val durationMillis: Long,
) : MediaProperties

@InternalMediampApi
public fun MediaProperties.copy(
    title: String? = this.title,
    durationMillis: Long = this.durationMillis,
): MediaProperties {
    @OptIn(InternalMediampApi::class)
    return MediaPropertiesImpl(
        title = title,
        durationMillis = durationMillis,
    )
}