/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.metadata

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.openani.mediamp.InternalForInheritanceMediampApi

@SubclassOptInRequired(InternalForInheritanceMediampApi::class)
public interface TrackGroup<T> {
    public val current: StateFlow<T?>
    public val candidates: Flow<List<T>>

    public fun select(track: T?): Boolean
}

@Suppress("UNCHECKED_CAST")
public fun <T> emptyTrackGroup(): TrackGroup<T> = EmptyTrackGroup as TrackGroup<T>

@OptIn(InternalForInheritanceMediampApi::class)
private object EmptyTrackGroup : TrackGroup<Nothing> {
    override val current: StateFlow<Nothing?> = MutableStateFlow(null)
    override val candidates: Flow<List<Nothing>> = emptyFlow()

    override fun select(track: Nothing?): Boolean = false
}

//fun <T> mutableTrackGroupOf(vararg tracks: T): MutableTrackGroup<T> = MutableTrackGroup<T>().apply {
//    candidates.value = tracks.toList()
//}

//public fun <T> trackGroupOf(vararg tracks: T): TrackGroup<T> = MutableTrackGroup<T>(tracks.toList())
