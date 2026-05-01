/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.pointed
import org.openani.mediamp.ffmpeg.ffi.av_packet_alloc
import org.openani.mediamp.ffmpeg.ffi.av_packet_free
import org.openani.mediamp.ffmpeg.ffi.av_packet_unref
import org.openani.mediamp.ffmpeg.internal.NativeAVPacket

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
public actual class AVPacket : AutoCloseable {
    public actual companion object {
        public actual val NOPTS: Long = Long.MIN_VALUE
    }

    internal val native: NativeAVPacket = NativeAVPacket(av_packet_alloc()
        ?: throw FFmpegException(-12))

    actual override fun close() {
        av_packet_free(cValuesOf(native.ptr))
    }

    public actual fun unref() {
        av_packet_unref(native.ptr)
    }

    public actual fun streamIndex(): Int = native.ptr.pointed.stream_index

    public actual var pts: Long
        get() = native.ptr.pointed.pts
        set(value) { native.ptr.pointed.pts = value }

    public actual var dts: Long
        get() = native.ptr.pointed.dts
        set(value) { native.ptr.pointed.dts = value }
}
