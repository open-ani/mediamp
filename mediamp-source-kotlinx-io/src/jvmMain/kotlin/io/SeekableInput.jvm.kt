package org.openani.mediamp.io

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.File

/**
 * Opens a [SeekableInput] for the specified [path] using the [kotlinx.io.files.SystemFileSystem].
 * @param onFillBuffer a callback that is invoked when the buffer is empty and needs to be filled.
 */
@Suppress("FunctionName")
public actual fun SystemFileSeekableInput(
    path: Path,
    bufferSize: Int,
    onFillBuffer: (() -> Unit)?,
): SeekableInput = File(SystemFileSystem.resolve(path).toString())
    .toSeekableInput(bufferSize, onFillBuffer)
