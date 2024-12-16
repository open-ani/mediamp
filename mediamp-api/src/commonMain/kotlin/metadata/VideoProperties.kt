package org.openani.mediamp.metadata

import org.openani.mediamp.MediampInternalApi

public class VideoProperties @MediampInternalApi public constructor(
    public val title: String?,
    public val durationMillis: Long,
) {
    public fun copy(
        title: String? = this.title,
        durationMillis: Long = this.durationMillis,
    ): VideoProperties {
        @OptIn(MediampInternalApi::class)
        return VideoProperties(
            title = title,
            durationMillis = durationMillis,
        )
    }
}
