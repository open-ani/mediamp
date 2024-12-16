/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package org.openani.mediamp.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.io.emptySeekableInput
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Holds information about a video file.
 */
public interface VideoData {
    public val filename: String // 会显示在 UI

    /**
     * Returns the length of the video file in bytes.
     */
    public fun fileLength(): Long?

    public data class Stats(
        /**
         * The download speed in bytes per second.
         *
         * If the video data is not being downloaded, i.e. it is a local file,
         * [downloadSpeed] will be  -1.
         */
        val downloadSpeed: Long,

        /**
         * The upload speed in bytes per second.
         *
         * If this video data is not being uploaded, i.e. it is a local file,
         * the flow emits [FileSize.Unspecified].
         */
        val uploadRate: Long,
    ) {
        public companion object {
            public val Unspecified: Stats = Stats(-1, -1)
        }
    }

    public val networkStats: Flow<Stats>

    public val isCacheFinished: Flow<Boolean> get() = flowOf(false)

    /**
     * Opens a new input stream to the video file.
     * The returned [SeekableInput] needs to be closed when not used anymore.
     *
     * The returned [SeekableInput] must be closed before a new [createInput] can be made.
     * Otherwise, it is undefined behavior.
     */
    public suspend fun createInput(coroutineContext: CoroutineContext = EmptyCoroutineContext): SeekableInput

    public suspend fun close()
}

public fun emptyVideoData(): VideoData = EmptyVideoData

private object EmptyVideoData : VideoData {
    override val filename: String get() = ""
    override fun fileLength(): Long? = null

    override val networkStats: Flow<VideoData.Stats> =
        flowOf(VideoData.Stats.Unspecified)

    override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput = emptySeekableInput()
    override suspend fun close() {}
}
