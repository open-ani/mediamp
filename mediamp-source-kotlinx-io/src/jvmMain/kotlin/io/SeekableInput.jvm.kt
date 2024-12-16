package me.him188.ani.utils.io

import org.openani.mediamp.io.toSeekableInput

actual fun SystemPath.toSeekableInput(
    bufferSize: Int,
    onFillBuffer: (() -> Unit)?,
): SeekableInput = this.toFile().toSeekableInput(bufferSize, onFillBuffer)
