/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg.internal

import kotlinx.cinterop.CPointer
import org.openani.mediamp.ffmpeg.ffi.AVCodecContext
import org.openani.mediamp.ffmpeg.ffi.AVFormatContext
import org.openani.mediamp.ffmpeg.ffi.AVFrame
import org.openani.mediamp.ffmpeg.ffi.AVPacket
import org.openani.mediamp.ffmpeg.ffi.AVStream

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual class NativeAVFormatContext(val ptr: CPointer<AVFormatContext>)

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual class NativeAVCodecContext(val ptr: CPointer<AVCodecContext>)

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual class NativeAVPacket(val ptr: CPointer<AVPacket>)

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual class NativeAVFrame(val ptr: CPointer<AVFrame>)

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual class NativeAVStream(val ptr: CPointer<AVStream>)
