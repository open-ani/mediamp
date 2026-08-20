/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Same as `Dispatchers.IO`. On the JVM `IO` is a member of [Dispatchers], but on native
 * targets it is the extension property `kotlinx.coroutines.IO`, which needs its own import —
 * one that IDEs drop as "unused" whenever the native targets are excluded from analysis,
 * silently breaking the next iOS build. Reference this shared alias instead of importing
 * `kotlinx.coroutines.IO` per file.
 */
expect val Dispatchers.IO_: CoroutineDispatcher
