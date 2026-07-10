/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.metadata

import org.openani.mediamp.InternalMediampApi

public sealed interface Track

/*
 * Value equality on these types is load-bearing: player backends re-create track
 * instances on every native tracks-changed callback and compare the new candidate
 * list against the previous one to decide whether the selection must be reset.
 * With identity equality that comparison is always `false` for non-empty lists,
 * which resets the user's subtitle selection on every refresh (open-ani/animeko#1128).
 */

public class SubtitleTrack @InternalMediampApi constructor(
    public val id: String,
    public val internalId: String,
    public val language: String?,
    public val labels: List<TrackLabel>,
) : Track {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SubtitleTrack) return false
        return id == other.id &&
                internalId == other.internalId &&
                language == other.language &&
                labels == other.labels
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + internalId.hashCode()
        result = 31 * result + (language?.hashCode() ?: 0)
        result = 31 * result + labels.hashCode()
        return result
    }

    override fun toString(): String {
        return "SubtitleTrack(id='$id', internalId='$internalId', language=$language, labels=$labels)"
    }
}

public class AudioTrack @InternalMediampApi constructor(
    public val id: String,
    public val internalId: String,
    public val name: String?,
    public val labels: List<TrackLabel>,
) : Track {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioTrack) return false
        return id == other.id &&
                internalId == other.internalId &&
                name == other.name &&
                labels == other.labels
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + internalId.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + labels.hashCode()
        return result
    }

    override fun toString(): String {
        return "AudioTrack(id='$id', internalId='$internalId', name=$name, labels=$labels)"
    }
}

public class TrackLabel @InternalMediampApi constructor(
    public val language: String?, // "zh" 这指的是 value 的语言
    public val value: String // "CHS", "简日", "繁日"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrackLabel) return false
        return language == other.language && value == other.value
    }

    override fun hashCode(): Int {
        var result = language?.hashCode() ?: 0
        result = 31 * result + value.hashCode()
        return result
    }

    override fun toString(): String {
        return "TrackLabel(language=$language, value='$value')"
    }
}
