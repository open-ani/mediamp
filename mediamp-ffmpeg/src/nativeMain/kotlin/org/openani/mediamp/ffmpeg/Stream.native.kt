/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.cinterop.pointed
import org.openani.mediamp.ffmpeg.internal.NativeAVStream

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
public actual class Stream internal constructor(
    internal val native: NativeAVStream,
) {
    public actual val index: Int
        get() = native.ptr.pointed.index

    public actual val timeBase: Rational
        get() = Rational(native.ptr.pointed.time_base.num, native.ptr.pointed.time_base.den)

    public actual val codecType: Int
        get() = native.ptr.pointed.codecpar?.pointed?.codec_type ?: -1

    public actual val codecId: Int
        get() = native.ptr.pointed.codecpar?.pointed?.codec_id?.toInt() ?: -1
}
