/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.vlc.internal.io

import org.openani.mediamp.io.SeekableInput
import uk.co.caprica.vlcj.media.callback.DefaultCallbackMedia

internal class SeekableInputCallbackMedia(
    private val input: SeekableInput,
    private val onClose: () -> Unit,
) : DefaultCallbackMedia(true) {
    override fun onGetSize(): Long = input.size
    override fun onOpen(): Boolean {
        onSeek(0L)
        return true
    }

    override fun onRead(buffer: ByteArray, bufferSize: Int): Int {
        return try {
            input.read(buffer, 0, bufferSize)
        } catch (_: Exception) {
            -1
        }
    }

    override fun onSeek(offset: Long): Boolean {
        input.seekTo(offset)
        return true
    }

    public override fun onClose() {
        this.onClose.invoke()
    }
}
