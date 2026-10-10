/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import org.openani.mediamp.mpv.internal.D3D11ReadbackSurfaceBackend
import org.openani.mediamp.mpv.internal.D3D11SurfaceRingBackend
import org.openani.mediamp.mpv.internal.MpvBackendChange
import org.openani.mediamp.mpv.internal.MpvBackendSelection
import org.openani.mediamp.mpv.internal.WindowsOpenGLSurfaceBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MpvBackendSelectionTest {
    @Test
    fun `first selection and the same backend are kept`() {
        assertEquals(MpvBackendChange.KEEP, MpvBackendSelection.change(null, D3D11SurfaceRingBackend, playbackActive = true))
        assertEquals(
            MpvBackendChange.KEEP,
            MpvBackendSelection.change(D3D11SurfaceRingBackend, D3D11SurfaceRingBackend, playbackActive = true),
        )
    }

    @Test
    fun `a renderer switch replaces the backend only without playback`() {
        assertEquals(
            MpvBackendChange.REPLACE,
            MpvBackendSelection.change(WindowsOpenGLSurfaceBackend, D3D11ReadbackSurfaceBackend, playbackActive = false),
        )
        assertEquals(
            MpvBackendChange.REFUSE_DURING_PLAYBACK,
            MpvBackendSelection.change(D3D11SurfaceRingBackend, D3D11ReadbackSurfaceBackend, playbackActive = true),
        )
    }

    @Test
    fun `an unavailable WGL producer falls back to D3D11 readback from then on`() {
        val selection = MpvBackendSelection()
        assertSame(WindowsOpenGLSurfaceBackend, selection.resolve(WindowsOpenGLSurfaceBackend))

        assertSame(D3D11ReadbackSurfaceBackend, selection.markUnavailable(WindowsOpenGLSurfaceBackend))
        assertSame(D3D11ReadbackSurfaceBackend, selection.resolve(WindowsOpenGLSurfaceBackend))
        assertSame(D3D11SurfaceRingBackend, selection.resolve(D3D11SurfaceRingBackend))
    }

    @Test
    fun `a producer without a fallback stays selected`() {
        val selection = MpvBackendSelection()
        assertNull(selection.markUnavailable(D3D11SurfaceRingBackend))
        assertSame(D3D11SurfaceRingBackend, selection.resolve(D3D11SurfaceRingBackend))
    }
}
