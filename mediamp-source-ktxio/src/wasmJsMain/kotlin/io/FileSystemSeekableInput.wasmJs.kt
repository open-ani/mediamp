/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.io

import kotlinx.io.files.Path

/**
 * Browser wasm does not expose a synchronous system filesystem to Kotlin.
 */
@Suppress("FunctionName")
public actual fun SystemFileSeekableInput(
    path: Path,
    bufferSize: Int,
    onFillBuffer: (() -> Unit)?,
): SeekableInput {
    throw UnsupportedOperationException("SystemFileSeekableInput is not available on wasmJs. Use UriMediaData with an http(s) or blob URI.")
}
