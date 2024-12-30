/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
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

public class SystemFileMediaData(
    public val file: Path,
    private val bufferSize: Int = 8 * 1024,
) : MediaData {
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
) : MediaSource<SystemFileMediaData> {
    override suspend fun open(): SystemFileMediaData = SystemFileMediaData(path)
    override fun toString(): String = "SystemFileMediaSource(uri=$uri)"
}

@Throws(IOException::class)
public fun SystemFileMediaSource(
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
