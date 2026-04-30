/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

public actual class MediaInput : AutoCloseable {
    public actual fun open(url: String): Unit = stub()
    public actual fun findStreamInfo(): Int = stub()
    public actual val streamCount: Int get() = stub()
    public actual fun codecType(streamIndex: Int): Int = stub()
    public actual fun codecId(streamIndex: Int): Int = stub()
    public actual fun readPacket(packet: AVPacket): Int = stub()
    actual override fun close(): Unit = stub()
}

public actual class DecoderContext : AutoCloseable {
    public actual fun open(codecId: Int): Unit = stub()
    public actual fun sendPacket(packet: AVPacket?): Int = stub()
    public actual fun receiveFrame(frame: AVFrame): Int = stub()
    actual override fun close(): Unit = stub()
}

public actual class AVPacket : AutoCloseable {
    actual override fun close(): Unit = stub()
    public actual fun unref(): Unit = stub()
}

public actual class AVFrame : AutoCloseable {
    actual override fun close(): Unit = stub()
}

private inline fun stub(): Nothing = throw UnsupportedOperationException("Android FFI wrapper not yet implemented")
