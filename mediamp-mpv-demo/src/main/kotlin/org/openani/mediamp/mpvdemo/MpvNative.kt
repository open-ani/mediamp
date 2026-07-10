/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpvdemo

object MpvNative {
    init {
        val path = System.getProperty("mpvdemo.native.lib")
            ?: error("Missing -Dmpvdemo.native.lib=<path to libmpvskiabridge.dylib>")
        System.load(path)
    }

    interface UpdateListener {
        fun onRenderUpdate()
    }

    @JvmStatic external fun create(hwdec: String): Long
    @JvmStatic external fun setUpdateListener(ctx: Long, listener: UpdateListener?)

    /** Returns a retained MTLTexture pointer backed by the same IOSurface mpv renders into. */
    @JvmStatic external fun createSurface(ctx: Long, w: Int, h: Int, mtlDevicePtr: Long): Long
    @JvmStatic external fun renderFrame(ctx: Long)

    @JvmStatic external fun command(ctx: Long, args: Array<String>): Int
    @JvmStatic external fun getPropertyString(ctx: Long, name: String): String?
    @JvmStatic external fun getPropertyDouble(ctx: Long, name: String): Double
    @JvmStatic external fun setPropertyString(ctx: Long, name: String, value: String): Int
    @JvmStatic external fun destroy(ctx: Long)
}
