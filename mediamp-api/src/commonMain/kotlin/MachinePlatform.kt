/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import kotlinx.coroutines.CoroutineDispatcher

/**
 * A stable identity token for the current thread, compared with `==`.
 * Used by [AbstractMediampPlayer] for the fail-fast command thread check (spec §4).
 */
internal expect fun currentThreadToken(): Any

/**
 * The dispatcher [org.openani.mediamp.source.MediaData.close] runs on by default (spec §8):
 * the IO dispatcher where the platform has one; [kotlinx.coroutines.Dispatchers.Default] on
 * wasmJs, which has neither an IO dispatcher nor blocking IO.
 */
internal expect val defaultReleaseDispatcher: CoroutineDispatcher
