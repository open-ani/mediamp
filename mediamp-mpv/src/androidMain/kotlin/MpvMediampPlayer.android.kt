/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import android.os.Build
import kotlin.coroutines.CoroutineContext

actual class MpvMediampPlayer(
    context: Any,
    parentCoroutineContext: CoroutineContext,
    /**
     * Optional hook to customize mpv options (e.g. `demuxer-max-bytes`, `cache-secs`) right
     * before the native handle is initialized. See [JvmMpvMediampPlayer] for details.
     */
    configureOptions: ((MPVHandle) -> Unit)? = null,
) : JvmMpvMediampPlayer(
    context,
    parentCoroutineContext,
    configureOptions = configureOptions,
)

actual fun limitDemuxer(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1