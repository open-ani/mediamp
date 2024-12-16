package org.openani.mediamp.core.state

import kotlin.coroutines.CoroutineContext

fun interface PlayerStateFactory {
    /**
     * Creates a new [PlayerState]
     * [parentCoroutineContext] must have a [kotlinx.coroutines.Job] so that the player state is bound to the parent coroutine context scope.
     *
     * @param context the platform context to create the underlying player implementation.
     * It is only used by the constructor and not stored.
     */
    fun create(context: Context, parentCoroutineContext: CoroutineContext): PlayerState
}