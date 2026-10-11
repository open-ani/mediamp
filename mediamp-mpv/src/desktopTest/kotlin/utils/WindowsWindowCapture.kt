/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.utils

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import org.jetbrains.skiko.SkiaLayer
import java.awt.image.BufferedImage

/**
 * Captures a [SkiaLayer] the way DWM composes its window, via
 * `PrintWindow(PW_RENDERFULLCONTENT)`: Direct3D flip-model swap chains included, and
 * independent of whatever covers the window on screen (the windows-11-arm runner keeps an
 * OOBE privacy screen above every other window, so a screen capture never sees ours).
 */
internal object WindowsWindowCapture {
    private const val PW_RENDERFULLCONTENT = 0x00000002

    /** The layer's pixels, or null when the window cannot be printed. Call on the EDT. */
    fun capture(layer: SkiaLayer): BufferedImage? {
        val window = WinDef.HWND(Pointer(layer.windowHandle))
        val content = WinDef.HWND(Pointer(layer.contentHandle))
        val windowRect = WinDef.RECT()
        val contentRect = WinDef.RECT()
        if (!User32.INSTANCE.GetWindowRect(window, windowRect) ||
            !User32.INSTANCE.GetWindowRect(content, contentRect)
        ) {
            return null
        }
        val width = windowRect.right - windowRect.left
        val height = windowRect.bottom - windowRect.top
        if (width <= 0 || height <= 0) return null

        val screenDc = User32.INSTANCE.GetDC(null)
        val memoryDc = GDI32.INSTANCE.CreateCompatibleDC(screenDc)
        val bitmap = GDI32.INSTANCE.CreateCompatibleBitmap(screenDc, width, height)
        try {
            val previous = GDI32.INSTANCE.SelectObject(memoryDc, bitmap)
            val printed = User32.INSTANCE.PrintWindow(window, memoryDc, PW_RENDERFULLCONTENT)
            // GetDIBits requires the bitmap not to be selected into a DC.
            GDI32.INSTANCE.SelectObject(memoryDc, previous)
            if (!printed) return null

            val info = WinGDI.BITMAPINFO().apply {
                bmiHeader.biWidth = width
                bmiHeader.biHeight = -height // top-down rows
                bmiHeader.biPlanes = 1
                bmiHeader.biBitCount = 32
                bmiHeader.biCompression = WinGDI.BI_RGB
            }
            val pixels = Memory(width.toLong() * height * 4)
            if (GDI32.INSTANCE.GetDIBits(screenDc, bitmap, 0, height, pixels, info, WinGDI.DIB_RGB_COLORS) == 0) {
                return null
            }
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            image.setRGB(0, 0, width, height, pixels.getIntArray(0, width * height), 0, width)
            // PrintWindow draws the whole window from its window-rect origin; keep the layer.
            val x = (contentRect.left - windowRect.left).coerceIn(0, width - 1)
            val y = (contentRect.top - windowRect.top).coerceIn(0, height - 1)
            return image.getSubimage(
                x, y,
                (contentRect.right - contentRect.left).coerceIn(1, width - x),
                (contentRect.bottom - contentRect.top).coerceIn(1, height - y),
            )
        } finally {
            GDI32.INSTANCE.DeleteObject(bitmap)
            GDI32.INSTANCE.DeleteDC(memoryDc)
            User32.INSTANCE.ReleaseDC(null, screenDc)
        }
    }
}
