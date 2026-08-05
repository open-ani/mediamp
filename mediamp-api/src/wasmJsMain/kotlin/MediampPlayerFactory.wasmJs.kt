/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import kotlin.coroutines.CoroutineContext

public actual fun MediampPlayer(
    context: Any,
    parentCoroutineContext: CoroutineContext,
): MediampPlayer = MediampPlayerFactoryLoader.first()
    .create(context, parentCoroutineContext)

public object MediampPlayerFactoryLoader {
    private var factories: List<MediampPlayerFactory<*>> = listOf(WebMediampPlayer.Factory)

    /**
     * Register a [MediampPlayerFactory] implementation.
     *
     * Explicitly registered factories take precedence over the built-in
     * [WebMediampPlayer.Factory], so [first] deterministically returns the latest
     * registration (matching the JVM loader's precedence).
     */
    public fun register(factory: MediampPlayerFactory<*>) {
        factories = (listOf(factory) + factories).distinctBy { it.forClass }
    }

    public fun first(): MediampPlayerFactory<*> = factories.firstOrNull()
        ?: throw IllegalStateException("No MediampPlayerFactory implementation found on the classpath.")

    public fun getByInstance(mediampPlayer: MediampPlayer): MediampPlayerFactory<*> = factories.find {
        it.forClass.isInstance(mediampPlayer)
    } ?: throw IllegalStateException("No MediampPlayerFactory implementation found for $mediampPlayer.")
}
