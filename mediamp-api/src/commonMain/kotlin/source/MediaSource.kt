/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.source

import kotlin.coroutines.cancellation.CancellationException

/**
 * A source of the video data [S].
 *
 * [MediaSource]s are stateless: They only represent a location of the resource, not holding file descriptors or network connections, etc.
 *
 * ## Obtaining data stream
 *
 * To get the input stream of the video file, two steps are needed:
 * 1. Open a [MediaData] using [open].
 * 2. Use [MediaData.createInput] to get the input stream [SeekableInput].
 *
 * Note that both [MediaData] and [SeekableInput] are [AutoCloseable] and needs to be properly closed.
 *
 * In the BitTorrent scenario, [MediaSource.open] is to resolve magnet links, and to download the torrent metadata file.
 * [MediaData.createInput] is to start downloading the actual video file.
 * Though the actual implementation might start downloading very soon (e.g. when [MediaSource] is just created), so that
 * the video buffers more soon.
 *
 * @param S type of the stream
 */
public interface MediaSource<S : MediaData> {
    public val uri: String

    public val extraFiles: MediaExtraFiles

    /**
     * Opens the underlying video data.
     *
     * Note that [S] should be closed by the caller.
     *
     * Repeat calls to this function may return different instances so it may be desirable to store the result.
     *
     * @throws VideoSourceOpenException 当打开失败时抛出, 包含原因
     */
    @Throws(VideoSourceOpenException::class, CancellationException::class)
    public suspend fun open(): S
}
