/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import android.os.Build
import org.openani.mediamp.InternalMediampApi

actual fun limitDemuxer(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1

@kotlin.OptIn(InternalMediampApi::class)
actual fun attachSurface(ptr: Long, surface: Any): Boolean {
    check(surface is android.view.Surface) { "surface must be an android.view.Surface" }
    return nAttachAndroidSurface(ptr, surface)
}

@kotlin.OptIn(InternalMediampApi::class)
actual fun detachSurface(ptr: Long): Boolean {
    return nDetachAndroidSurface(ptr)
}