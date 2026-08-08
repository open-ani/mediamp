/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** wasmJs is single-threaded: a singleton token suffices. */
private object WasmThreadToken

internal actual fun currentThreadToken(): Any = WasmThreadToken

/** wasmJs has no IO dispatcher and no blocking IO. */
internal actual val defaultReleaseDispatcher: CoroutineDispatcher get() = Dispatchers.Default
