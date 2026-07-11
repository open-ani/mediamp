/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import org.openani.mediamp.features.FramePreview
import kotlin.coroutines.CoroutineContext

// Not implemented on Android: the surface-ring readback used for frame extraction is a
// desktop-only render path. (Android apps typically use the ExoPlayer backend, which has
// its own FramePreview implementation.)
internal actual fun createMpvFramePreview(
    player: JvmMpvMediampPlayer,
    context: Any,
    parentCoroutineContext: CoroutineContext,
): FramePreview? = null
