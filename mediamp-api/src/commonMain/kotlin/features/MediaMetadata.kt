/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.features

import kotlinx.coroutines.flow.Flow
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.Chapter
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.metadata.TrackGroup

/**
 * An optional feature of the [org.openani.mediamp.MediampPlayer] that allows managing audio tracks.
 */
@SubclassOptInRequired(InternalForInheritanceMediampApi::class)
public interface MediaMetadata : Feature {
    /**
     * The group of audio tracks, if supported. Returns `null` if audio tracks are not supported.
     */
    public val audioTracks: TrackGroup<AudioTrack>?

    /**
     * The group of subtitle tracks, if supported. Returns `null` if subtitles are not supported.
     */
    public val subtitleTracks: TrackGroup<SubtitleTrack>?

    /**
     * The list of chapters, if supported. Returns `null` if chapters are not supported.
     */
    public val chapters: Flow<List<Chapter>?>

    public companion object Key : FeatureKey<MediaMetadata>
}
