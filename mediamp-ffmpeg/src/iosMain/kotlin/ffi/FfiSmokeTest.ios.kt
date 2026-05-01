/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg.ffi

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import org.openani.mediamp.ffmpeg.ffi.av_version_info

@OptIn(ExperimentalForeignApi::class)
internal actual fun avVersionInfo(): String = av_version_info()?.toKString() ?: "unknown"
