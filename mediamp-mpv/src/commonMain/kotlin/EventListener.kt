/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

interface EventListener {
    /**
     * Notify a property change with type MPV_FORMAT_NONE.
     */
    fun onPropertyChange(name: String)
    /**
     * Notify a property change with type MPV_FORMAT_FLAG.
     */
    fun onPropertyChange(name: String, value: Boolean)
    /**
     * Notify a property change with type MPV_FORMAT_INT64.
     */
    fun onPropertyChange(name: String, value: Long)
    /**
     * Notify a property change with type MPV_FORMAT_DOUBLE.
     */
    fun onPropertyChange(name: String, value: Double)
    /**
     * Notify a property change with type MPV_FORMAT_STRING.
     */
    fun onPropertyChange(name: String, value: String)

    /**
     * Notify that the current file stopped playing (`MPV_EVENT_END_FILE`).
     *
     * @param reason `mpv_end_file_reason`: 0=EOF, 2=STOP, 3=QUIT, 4=ERROR, 5=REDIRECT
     * @param mpvError `mpv_error` code, only meaningful when [reason] is 4 (ERROR)
     */
    fun onEndFile(reason: Int, mpvError: Int)
}
