package org.openani.mediamp.core.metadata

import kotlinx.coroutines.flow.MutableStateFlow
import org.openani.mediamp.metadata.TrackGroup

internal class MutableTrackGroup<T> internal constructor(
    initialCandidates: List<T> = emptyList(),
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
