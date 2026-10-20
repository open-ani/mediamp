/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

import org.openani.mediamp.features.FramePreview
import kotlin.time.Duration

/**
 * A [FramePreview] whose [close] only starts its native teardown on another thread.
 *
 * The owning player waits via [awaitClosed] before running its own native teardown: the
 * preview decoder is a second mpv instance with its own render context, and the two
 * teardowns must not run concurrently (see `JvmMpvMediampPlayer.closeImpl`).
 */
internal interface AsyncClosingFramePreview : FramePreview, AutoCloseable {
    /**
     * Blocks until the teardown started by [close] has finished. Returns false when [timeout]
     * elapses first. Only meaningful after [close] was called.
     */
    fun awaitClosed(timeout: Duration): Boolean
}
