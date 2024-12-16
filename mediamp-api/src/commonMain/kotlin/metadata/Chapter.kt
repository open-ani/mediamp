package org.openani.mediamp.metadata

import androidx.compose.runtime.Immutable

@Immutable
public class Chapter(
    public val name: String,
    public val durationMillis: Long,
    public val offsetMillis: Long
)
