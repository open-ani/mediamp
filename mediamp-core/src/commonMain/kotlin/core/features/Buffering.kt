/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.core.features

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.Flow

interface Buffering : Feature {
//    /**
//     * 区块列表. 每个区块的宽度由 [Chunk.weight] 决定.
//     *
//     * 所有 chunks 的 weight 之和应当 (约) 等于 1, 否则将会导致绘制超出进度条的区域 (即会被忽略).
//     */
//    val chunks: List<Chunk>
//
//    /**
//     * 当前的版本. 当 [chunks] 更新时, 该值会递增.
//     */
//    val version: Int
//
//    /**
//     * 是否已经全部缓存完成. 当已经缓存完成时, UI 可能会优化性能, 不再考虑 [chunks] 更新.
//     */
//    val isFinished: Boolean


    /**
     * 是否正在 buffer (暂停视频中)
     */
    val isBuffering: Flow<Boolean>

    /**
     * `0..100`
     */
    val bufferedPercentage: Flow<Int>

    companion object Key : FeatureKey<Buffering>
}

// Not stable
interface Chunk {
    @Stable
    val weight: Float // always return the same value

    // This can change, and change will not notify compose state
    val state: ChunkState
}

enum class ChunkState {
    /**
     * 初始状态
     */
    NONE,

    /**
     * 正在下载
     */
    DOWNLOADING,

    /**
     * 下载完成
     */
    DONE,

    /**
     * 对应 BT 的没有任何 peer 有这个 piece 的状态
     */
    NOT_AVAILABLE
}
