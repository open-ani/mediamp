/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.openani.mediamp.internal.MediampInternalApi
import org.openani.mediamp.io.SeekableInput
import kotlin.coroutines.CoroutineContext

public open class UriMediaSource(
    override val uri: String,
    public val headers: Map<String, String> = emptyMap(),
    override val extraFiles: MediaExtraFiles,
) : MediaSource<UriVideoData> {
    override suspend fun open(): UriVideoData {
        return UriVideoData(uri)
    }

    override fun toString(): String {
        return "HttpStreamingVideoSource(uri='$uri')"
    }
}


public class UriVideoData(
    public val url: String,
) : VideoData {
    override fun fileLength(): Long? = null

    @OptIn(MediampInternalApi::class)
    override val networkStats: Flow<NetStats> = flowOf(NetStats(0, 0))

    override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput {
        throw UnsupportedOperationException()
    }

    override suspend fun close() {
    }
}