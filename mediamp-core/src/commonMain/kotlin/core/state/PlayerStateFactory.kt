/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.core.state

import kotlin.coroutines.CoroutineContext

fun interface PlayerStateFactory<C> {
    /**
     * Creates a new [MediampPlayer]
     * [parentCoroutineContext] must have a [kotlinx.coroutines.Job] so that the player state is bound to the parent coroutine context scope.
     *
     * @param context the platform context to create the underlying player implementation.
     * It is only used by the constructor and not stored.
     */
    fun create(context: C, parentCoroutineContext: CoroutineContext): MediampPlayer
}
