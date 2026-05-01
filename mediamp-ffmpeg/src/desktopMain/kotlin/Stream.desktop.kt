/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import org.openani.mediamp.ffmpeg.internal.NativeAVStream

public actual class Stream internal constructor(
    internal val native: NativeAVStream,
) {
    public actual val index: Int
        get() = native.index()

    public actual val timeBase: Rational
        get() = Rational(native.time_base().num(), native.time_base().den())

    public actual val codecType: Int
        get() = native.codecpar().codec_type()

    public actual val codecId: Int
        get() = native.codecpar().codec_id()
}
