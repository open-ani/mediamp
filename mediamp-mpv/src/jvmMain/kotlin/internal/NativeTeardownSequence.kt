/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

/**
 * Requests native work to stop regardless of graphics cleanup or failure-reporting success,
 * but only destroys the handle after [prepare] completed. Returns whether handle destruction
 * was permitted.
 */
internal fun runNativeTeardownSequence(
    prepare: () -> Unit,
    stop: () -> Unit,
    destroy: () -> Unit,
    close: () -> Unit,
    onPrepareFailure: (Throwable) -> Unit,
): Boolean {
    val prepareFailure = runCatching(prepare).exceptionOrNull()
    if (prepareFailure != null) {
        runCatching { onPrepareFailure(prepareFailure) }
    }

    runCatching(stop)
    if (prepareFailure != null) return false

    runCatching(destroy)
    runCatching(close)
    return true
}
