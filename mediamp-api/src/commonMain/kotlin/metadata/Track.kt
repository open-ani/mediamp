package org.openani.mediamp.metadata

public sealed interface Track

public class SubtitleTrack internal constructor(
    public val id: String,
    public val internalId: String,
    public val language: String?,
    public val labels: List<TrackLabel>,
) : Track

public class AudioTrack internal constructor(
    public val id: String,
    public val internalId: String,
    public val name: String?,
    public val labels: List<TrackLabel>,
) : Track

public class TrackLabel internal constructor(
    public val language: String?, // "zh" 这指的是 value 的语言
    public val value: String // "CHS", "简日", "繁日"
)
