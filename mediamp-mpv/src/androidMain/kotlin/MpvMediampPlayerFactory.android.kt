package org.openani.mediamp.mpv

import org.openani.mediamp.MediampPlayerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

class MpvMediampPlayerFactory : MediampPlayerFactory<MpvMediampPlayer> {
    override val forClass: KClass<MpvMediampPlayer> = MpvMediampPlayer::class

    override fun create(context: Any, parentCoroutineContext: CoroutineContext): MpvMediampPlayer {
        return MpvMediampPlayer(context, parentCoroutineContext)
    }

    /**
     * Creates a new [MpvMediampPlayer].
     *
     * @param configureOptions optional hook to customize mpv options (e.g. `demuxer-max-bytes`,
     *   `cache-secs`) right before the native handle is initialized. See [JvmMpvMediampPlayer]
     *   for details.
     */
    fun create(
        context: Any,
        parentCoroutineContext: CoroutineContext,
        configureOptions: ((MPVHandle) -> Unit)?,
    ): MpvMediampPlayer {
        return MpvMediampPlayer(context, parentCoroutineContext, configureOptions = configureOptions)
    }
}