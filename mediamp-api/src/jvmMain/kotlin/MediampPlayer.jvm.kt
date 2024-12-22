/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import java.io.File
import java.net.URI
import java.net.URL

/**
 * Plays the video at the specified [uri].
 */
public suspend inline fun MediampPlayer.playUri(uri: URI): Unit =
    playUri(uri.toString())

/**
 * Plays the video at the specified [url].
 */
public suspend inline fun MediampPlayer.playUri(url: URL): Unit =
    playUri(url.toString())


/**
 * Plays the file.
 */
public suspend fun MediampPlayer.playFile(file: File) {
    playUri(file.toURI().toString())
}
