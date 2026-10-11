/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.internal

/** What happens to a player's current backend when Skiko's live redrawer maps to [target]. */
internal enum class MpvBackendChange {
    /** Nothing selected yet, or the same backend: attach or keep it. */
    KEEP,

    /** Release the current backend's consumer and producer, then attach the new one. */
    REPLACE,

    /**
     * Freeing mpv's render context while video is active forcibly disables video
     * (render.h), and reloading the video output transparently is not supported — the same
     * rule as the Linux GLX environment replacement. The player keeps its backend and the
     * surface shows no video until the player is recreated.
     */
    REFUSE_DURING_PLAYBACK,
}

/**
 * Which [MpvSurfaceBackend] a player renders through for Skiko's live redrawer. The
 * redrawer decides ([currentSurfaceBackend]); this adds the per-machine knowledge of
 * producers that could not be created, so their fallback is chosen instead from then on.
 * Confined to the UI thread, like every surface entry point.
 */
internal class MpvBackendSelection {
    private val unavailable = mutableSetOf<MpvSurfaceBackend>()

    /** [preferred] (what the live redrawer maps to), or its fallback once its producer failed. */
    fun resolve(preferred: MpvSurfaceBackend): MpvSurfaceBackend =
        if (preferred in unavailable) fallbackWhenUnavailable(preferred) ?: preferred else preferred

    /**
     * Records that [backend]'s producer could not be created and returns the backend to use
     * instead, or null when there is none (the backend then stays selected, as before).
     */
    fun markUnavailable(backend: MpvSurfaceBackend): MpvSurfaceBackend? =
        fallbackWhenUnavailable(backend)?.also { unavailable += backend }

    companion object {
        /**
         * The WGL producer needs a hardware OpenGL driver, which a machine whose Skiko
         * redrawer is (or was briefly) OpenGL may not have: Skiko itself creates its OpenGL
         * redrawer on the GL 1.1 generic implementation and only fails on its first frame.
         * D3D11 readback needs nothing from Skiko's renderer and works wherever Direct3D 11
         * does, WARP included.
         */
        fun fallbackWhenUnavailable(backend: MpvSurfaceBackend): MpvSurfaceBackend? = when (backend) {
            WindowsOpenGLSurfaceBackend -> D3D11ReadbackSurfaceBackend
            else -> null
        }

        fun change(current: MpvSurfaceBackend?, target: MpvSurfaceBackend, playbackActive: Boolean): MpvBackendChange =
            when {
                current == null || current === target -> MpvBackendChange.KEEP
                playbackActive -> MpvBackendChange.REFUSE_DURING_PLAYBACK
                else -> MpvBackendChange.REPLACE
            }
    }
}
