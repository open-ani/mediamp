/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg.internal

/**
 * Platform-specific native handle for `AVFormatContext`.
 *
 * On JVM (Desktop/Android) this is `org.bytedeco.ffmpeg.avformat.AVFormatContext`.
 * On Native (iOS) this is `CPointer<ffmpeg.AVFormatContext>`.
 */
internal expect class NativeAVFormatContext

/**
 * Platform-specific native handle for `AVCodecContext`.
 */
internal expect class NativeAVCodecContext

/**
 * Platform-specific native handle for `AVPacket`.
 */
internal expect class NativeAVPacket

/**
 * Platform-specific native handle for `AVFrame`.
 */
internal expect class NativeAVFrame

/**
 * Platform-specific native handle for `AVStream`.
 */
internal expect class NativeAVStream

