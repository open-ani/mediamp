/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg.internal

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVStream

internal actual typealias NativeAVFormatContext = AVFormatContext
internal actual typealias NativeAVCodecContext = AVCodecContext
internal actual typealias NativeAVPacket = AVPacket
internal actual typealias NativeAVFrame = AVFrame
internal actual typealias NativeAVStream = AVStream
