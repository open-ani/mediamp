/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import android.os.Build
import android.os.Looper
import kotlin.coroutines.CoroutineContext

actual class MpvMediampPlayer(
    context: Any,
    parentCoroutineContext: CoroutineContext
) : JvmMpvMediampPlayer(
    context,
    parentCoroutineContext,
    isOnMainThread = {
        // Fail-fast check for playback commands (spec §4): they must run on the thread of
        // the machine's main dispatcher, which is Android's main looper thread.
        val mainLooper = Looper.getMainLooper()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mainLooper.isCurrentThread
        } else {
            mainLooper.thread === Thread.currentThread()
        }
    },
)

actual fun limitDemuxer(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1