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
import kotlinx.io.IOException
import org.openani.mediamp.MediampInternalApi
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.io.emptySeekableInput
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Holds information about a video file.
 */
public interface VideoData {
    public val filename: String

    /**
     * Returns the length of the video file in bytes, or `null` if not known.
     */
    @Throws(IOException::class)
    public fun fileLength(): Long?

    /**
     * Subscribe to network stats updates of this video data, if known.
     */
    public val networkStats: Flow<NetStats> // todo: remove networkStats

    // TODO: 2024/12/16 remove isCacheFinished
    public val isCacheFinished: Flow<Boolean> get() = flowOf(false)

    /**
     * Opens a new input stream to the video file.
     * The returned [SeekableInput] needs to be closed when not used anymore.
     *
     * The returned [SeekableInput] must be closed before a new [createInput] can be made.
     * Otherwise, it is undefined behavior.
     */
    public suspend fun createInput(coroutineContext: CoroutineContext = EmptyCoroutineContext): SeekableInput

    /**
     * Closes the video data. // TODO: 2024/12/16 documentation
     */
    public suspend fun close() // TODO: 2024/12/16 make non-suspend?
}

public class NetStats @MediampInternalApi public constructor(
    /**
     * The download speed in bytes per second.
     *
     * May return `-1` if it is not known.
     */
    public val downloadSpeed: Long,

    /**
     * The upload speed in bytes per second.
     *
     * May return `-1` if it is not known.
     */
    public val uploadRate: Long,
)

public fun emptyVideoData(): VideoData = EmptyVideoData

private object EmptyVideoData : VideoData {
    override val filename: String get() = ""
    override fun fileLength(): Long? = null

    @OptIn(MediampInternalApi::class)
    override val networkStats: Flow<NetStats> = flowOf(NetStats(0, 0))

    override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput = emptySeekableInput()
    override suspend fun close() {}
}
