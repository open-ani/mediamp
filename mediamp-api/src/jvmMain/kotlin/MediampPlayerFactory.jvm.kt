/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import org.openani.mediamp.internal.MediampInternalApi
import java.util.ServiceLoader
import kotlin.coroutines.CoroutineContext

public actual fun MediampPlayer(
    context: Any,
    parentCoroutineContext: CoroutineContext,
): MediampPlayer =
    @OptIn(MediampInternalApi::class)
    MediampPlayerFactoryLoader.first()
        .create(context, parentCoroutineContext)

@MediampInternalApi
public object MediampPlayerFactoryLoader {
    private val factories = ServiceLoader.load(MediampPlayerFactory::class.java).toList()

    public fun first(): MediampPlayerFactory<*> = factories.firstOrNull()
        ?: throw IllegalStateException("No MediampPlayerFactory implementation found on the classpath.")

    public fun getByInstance(mediampPlayer: MediampPlayer): MediampPlayerFactory<*> = factories.find {
        it.forClass.isInstance(mediampPlayer)
    } ?: throw IllegalStateException("No MediampPlayerFactory implementation found for $mediampPlayer.")
}
