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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import me.him188.ani.utils.coroutines.runInterruptible
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.exists
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.io.toSeekableInput
import kotlin.coroutines.CoroutineContext

public class FileVideoData(
    public val fileSystem: FileSystem,
    public val file: Path,
) : VideoData {
    override val filename: String
        get() = file.name

    override fun fileLength(): Long? = fileSystem.metadataOrNull(file)?.size

    override val networkStats: Flow<VideoData.Stats> = MutableStateFlow(VideoData.Stats.Unspecified)

    override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput =
        runInterruptible { file.toSeekableInput() }

    override suspend fun close() {
        // no-op
    }
}

public class FileVideoSource(
    public val fileSystem: FileSystem,
    public val file: Path,
    override val extraFiles: MediaExtraFiles,
) : VideoSource<FileVideoData> {
    init {
        require(file.exists()) { "File does not exist: $file" }
    }

    override val uri: String
        get() = "file://${file.absolutePath}"

    override suspend fun open(): FileVideoData = FileVideoData(file)

    override fun toString(): String = "FileVideoSource(uri=$uri)"
}
