/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.core.features

/**
 * An optional feature of the [org.openani.mediamp.core.state.MediampPlayer] that allows taking screenshots of the current video frame.
 */
interface Screenshots : Feature {
    /**
     * Take a screenshot of the current video frame and saves it to a file on the system filesystem.
     */
    suspend fun takeScreenshot(destinationFile: String)

    companion object Key : FeatureKey<Screenshots>
}
