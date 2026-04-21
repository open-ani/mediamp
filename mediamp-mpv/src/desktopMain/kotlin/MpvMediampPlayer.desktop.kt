/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.Image
import kotlin.coroutines.CoroutineContext

actual class MpvMediampPlayer(
    context: Any,
    parentCoroutineContext: CoroutineContext
) : JvmMpvMediampPlayer(context, parentCoroutineContext) {
    internal var backendTexture: BackendTexture? = null
    internal var image: Image? = null

    companion object {
        internal const val GL_TEXTURE_2D = 0x0DE1
        internal const val GL_RGBA8 = 0x8058
    }
}
actual fun limitDemuxer(): Boolean = false