/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.MediampPlayer

/**
 * [MediampPlayer] backed by libmpv.
 *
 * The playback state model is implemented by the shared state machine
 * ([org.openani.mediamp.AbstractMediampPlayer], spec: `docs/playback-state-v2.md`); the mpv
 * backend adapts native mpv events and properties onto it.
 */
@OptIn(InternalForInheritanceMediampApi::class)
expect class MpvMediampPlayer : MediampPlayer
