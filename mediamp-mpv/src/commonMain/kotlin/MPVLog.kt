/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlin.concurrent.Volatile

/**
 * A parsed mpv log line emitted by `MPV_EVENT_LOG_MESSAGE`.
 */
public class MPVLogMessage(
    public val level: Int,
    public val prefix: String,
    public val line: String,
) {
    public val isError: Boolean get() = level <= 20
}

public fun interface MPVLogHandler {
    public fun onLog(message: MPVLogMessage)
}

@Volatile
private var configuredLogHandler: MPVLogHandler? = null

internal fun setMPVLogHandler(handler: MPVLogHandler?) {
    configuredLogHandler = handler
}

@Suppress("unused") // Called from JNI.
public fun onNativeLog(level: Int, prefix: String, message: String) {
    val normalizedLine = message.trimEnd('\r', '\n')
    if (normalizedLine.isEmpty()) {
        return
    }
    configuredLogHandler?.onLog(
        MPVLogMessage(
            level = level,
            prefix = prefix,
            line = normalizedLine,
        ),
    )
}
