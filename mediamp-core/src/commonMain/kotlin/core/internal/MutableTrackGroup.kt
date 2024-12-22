/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.core.internal

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
