/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package org.openani.mediamp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.VideoData
import org.openani.mediamp.source.VideoSource
import kotlin.coroutines.CoroutineContext

public open class HttpStreamingVideoSource(
    override val uri: String,
    private val filename: String,
    override val extraFiles: MediaExtraFiles,
) : VideoSource<HttpStreamingVideoData> {
    override suspend fun open(): HttpStreamingVideoData {
        return HttpStreamingVideoData(uri, filename)
    }

    override fun toString(): String {
        return "HttpStreamingVideoSource(filename='$filename')"
    }
}


public class HttpStreamingVideoData(
    public val url: String,
    override val filename: String
) : VideoData {
    override fun fileLength(): Long? = null

    override val networkStats: Flow<VideoData.Stats> = flowOf(VideoData.Stats.Unspecified)

    override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput {
        throw UnsupportedOperationException()
    }

    override suspend fun close() {
    }
}