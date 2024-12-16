package org.openani.mediamp.metadata

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

public interface TrackGroup<T> {
    public val current: StateFlow<T?>
    public val candidates: Flow<List<T>>

    public fun select(track: T?): Boolean
}

public fun <T> emptyTrackGroup(): TrackGroup<T> = object : TrackGroup<T> {
    override val current: StateFlow<T?> = MutableStateFlow<T?>(null)
    override val candidates: Flow<List<T>> = emptyFlow()

    override fun select(track: T?): Boolean = false
}

//fun <T> mutableTrackGroupOf(vararg tracks: T): MutableTrackGroup<T> = MutableTrackGroup<T>().apply {
//    candidates.value = tracks.toList()
//}

public fun <T> trackGroupOf(vararg tracks: T): TrackGroup<T> = MutableTrackGroup<T>(tracks.toList())

public class MutableTrackGroup<T> internal constructor(
    initialCandidates: List<T> = emptyList()
) : TrackGroup<T> {
    override val current: MutableStateFlow<T?> = MutableStateFlow(null)
    override val candidates: MutableStateFlow<List<T>> = MutableStateFlow(initialCandidates)

    override fun select(track: T?): Boolean {
        if (track == null) {
            current.value = null
            return true
        }
        if (track !in candidates.value) {
            return false
        }
        current.value = track
        return true
    }
}