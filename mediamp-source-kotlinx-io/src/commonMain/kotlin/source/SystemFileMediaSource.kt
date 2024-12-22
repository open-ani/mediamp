/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.openani.mediamp.internal.MediampInternalApi
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.io.SystemFileSeekableInput
import kotlin.coroutines.CoroutineContext

public class SystemFileVideoData(
    public val file: Path,
    private val bufferSize: Int = 8 * 1024,
) : VideoData {
    override val filename: String
        get() = file.name

    override fun fileLength(): Long? = SystemFileSystem.metadataOrNull(file)?.size

    @OptIn(MediampInternalApi::class)
    override val networkStats: Flow<NetStats> = flowOf(NetStats(0, 0))

    override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput =
        withContext(Dispatchers.IO) { SystemFileSeekableInput(file, bufferSize) }

    override suspend fun close() {
        // no-op
    }
}

public class SystemFileMediaSource internal constructor(
    public val path: Path,
    override val extraFiles: MediaExtraFiles,
    override val uri: String,
) : MediaSource<SystemFileVideoData> {
    override suspend fun open(): SystemFileVideoData = SystemFileVideoData(path)
    override fun toString(): String = "SystemFileVideoSource(uri=$uri)"
}

@Throws(IOException::class)
public fun SystemFileVideoSource(
    path: Path,
    extraFiles: MediaExtraFiles = MediaExtraFiles.Empty,
): SystemFileMediaSource {
    check(SystemFileSystem.exists(path)) { "File does not exist: $path" }
    return SystemFileMediaSource(
        path,
        extraFiles,
        "file://${SystemFileSystem.resolve(path)}",
    )
}
