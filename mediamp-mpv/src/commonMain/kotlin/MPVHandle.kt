/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import org.openani.mediamp.io.SeekableInput
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalStdlibApi::class, ExperimentalAtomicApi::class)
class MPVHandle private constructor(ptr: Long) : AutoCloseable {
    private var eventListener: EventListener? = null
    private var renderUpdateListener: RenderUpdateListener? = null
    // Atomic so close() can claim the id exactly once. Native code tolerates stale ids
    // (handle_registry.cpp), but only the first close() should report finalizing.
    private val nativePtr = AtomicLong(ptr)

    /**
     * Opaque id of the native player (handle_registry.cpp), not a memory address. Calls
     * made with it after [close] find no player and return their "unavailable" value,
     * and ids are never reused, so it is safe to capture (e.g. in render consumers).
     */
    internal val ptr: Long
        get() = nativePtr.load().takeIf { it != 0L } ?: error("MPVHandle has already been closed")

    constructor(context: Any) : this(createHandle(context)) {
        if (ptr == 0L) throw IllegalStateException("Failed to create native mpv handle")
    }

    fun initialize(): Boolean {
        return nInitialize(ptr)
    }

    fun setEventListener(listener: EventListener) {
        eventListener = listener
        nSetEventListener(ptr, listener)
    }

    internal fun setRenderUpdateListener(listener: RenderUpdateListener?): Boolean {
        renderUpdateListener = listener
        return nSetRenderUpdateListener(ptr, listener)
    }

    fun command(vararg command: String): Boolean {
        return nCommand(ptr, command)
    }

    fun option(key: String, value: String): Boolean {
        return nOption(ptr, key, value)
    }

    /**
     * The property as an Int, clamped to the Int range; 0 when mpv cannot provide it.
     * Use [getPropertyLongOrNull] where "unavailable" must not read as 0.
     */
    fun getPropertyInt(name: String): Int {
        return nGetPropertyInt(ptr, name)
    }

    /** The property as a Boolean; false when unavailable (see [getPropertyBooleanOrNull]). */
    fun getPropertyBoolean(name: String): Boolean {
        return nGetPropertyBoolean(ptr, name)
    }

    /** The property as a Double; 0.0 when unavailable (see [getPropertyDoubleOrNull]). */
    fun getPropertyDouble(name: String): Double {
        return nGetPropertyDouble(ptr, name)
    }

    /** The property as a 64-bit integer, or null when mpv cannot provide it. */
    fun getPropertyLongOrNull(name: String): Long? {
        val out = LongArray(1)
        return if (nTryGetPropertyLong(ptr, name, out)) out[0] else null
    }

    /** The property as a Double, or null when mpv cannot provide it. */
    fun getPropertyDoubleOrNull(name: String): Double? {
        val out = DoubleArray(1)
        return if (nTryGetPropertyDouble(ptr, name, out)) out[0] else null
    }

    /** The property as a Boolean, or null when mpv cannot provide it. */
    fun getPropertyBooleanOrNull(name: String): Boolean? {
        val out = BooleanArray(1)
        return if (nTryGetPropertyBoolean(ptr, name, out)) out[0] else null
    }

    fun getPropertyString(name: String): String? {
        return nGetPropertyString(ptr, name)
    }

    fun setPropertyInt(name: String, value: Int): Boolean {
        return nSetPropertyInt(ptr, name, value)
    }

    fun setPropertyBoolean(name: String, value: Boolean): Boolean {
        return nSetPropertyBoolean(ptr, name, value)
    }

    fun setPropertyDouble(name: String, value: Double): Boolean {
        return nSetPropertyDouble(ptr, name, value)
    }

    fun setPropertyString(name: String, value: String): Boolean {
        return nSetPropertyString(ptr, name, value)
    }

    fun observeProperty(name: String, format: MPVFormat, replyData: Long = 0L): Boolean {
        return nObserveProperty(ptr, name, format.nativeValue, replyData)
    }

    fun unobserveProperty(replyData: Long): Boolean {
        return nUnobserveProperty(ptr, replyData)
    }

    fun registerSeekableInput(input: SeekableInput, uri: String): String {
        // On failure the native layer throws a specific IllegalArgumentException /
        // IllegalStateException with the concrete reason, so it normally does not return
        // false; the check remains only as a defensive fallback.
        if (!nRegisterSeekableInput(ptr, input, uri, input.size)) {
            error("Failed to register SeekableInput for mpv stream_cb: $uri")
        }
        return uri
    }

    fun unregisterSeekableInput(uri: String): Boolean {
        return nUnregisterSeekableInput(ptr, uri)
    }

    /**
     * Stop this `mpv_context` instance, which will run into the unrecoverable state.
     *
     * You will not expect to call any method except [close] after calling this function.
     */
    fun destroy(): Boolean {
        val currentPtr = nativePtr.load()
        if (currentPtr == 0L) {
            return false
        }
        return nDestroy(currentPtr)
    }

    override fun close() {
        // Claim the pointer atomically; only the caller that observes the non-zero value
        // proceeds to nFinalize, so the native instance is deleted at most once.
        val currentPtr = nativePtr.exchange(0L)
        if (currentPtr == 0L) {
            return
        }
        nFinalize(currentPtr)
    }

    public companion object {
        private fun createHandle(context: Any): Long {
            LibraryLoader.loadLibraries(context)
            return nMake(context)
        }

        public fun setRuntimeLibraryDirectory(path: String, extractRuntimeLibrary: Boolean) {
            LibraryLoader.setRuntimeLibraryDirectory(path, extractRuntimeLibrary)
        }

        public fun useDefaultRuntimeLibraryDirectory() {
            LibraryLoader.useDefaultRuntimeLibraryDirectory()
        }

        public fun setLogHandler(handler: MPVLogHandler?) {
            MPVLog.setHandler(handler)
        }
    }
}

@Suppress("unused")
enum class MPVFormat(
    /** The `mpv_format` value from mpv/client.h, passed to native code as is. */
    val nativeValue: Int,
) {
    MPV_FORMAT_NONE(0),
    MPV_FORMAT_STRING(1),
    MPV_FORMAT_OSD_STRING(2),
    MPV_FORMAT_FLAG(3),
    MPV_FORMAT_INT64(4),
    MPV_FORMAT_DOUBLE(5),
    MPV_FORMAT_NODE(6),
    MPV_FORMAT_NODE_ARRAY(7),
    MPV_FORMAT_NODE_MAP(8),
    MPV_FORMAT_BYTE_ARRAY(9),
}

@Suppress("unused")
object MPVEvent {
    const val NONE: Int = 0
    const val SHUTDOWN: Int = 1
    const val GET_PROPERTY_REPLY: Int = 3
    const val SET_PROPERTY_REPLY: Int = 4
    const val COMMAND_REPLY: Int = 5
    const val START_FILE: Int = 6
    const val END_FILE: Int = 7
    const val FILE_LOADED: Int = 8
    const val CLIENT_MESSAGE: Int = 16
    const val VIDEO_RECONFIG: Int = 17
    const val AUDIO_RECONFIG: Int = 18
    const val SEEK: Int = 20
    const val PLAYBACK_RESTART: Int = 21
    const val QUEUE_OVERFLOW: Int = 24
    const val HOOK: Int = 25
}

private external fun nMake(context: Any): Long
private external fun nInitialize(ptr: Long): Boolean
private external fun nSetEventListener(ptr: Long, eventListener: EventListener): Boolean
private external fun nSetRenderUpdateListener(ptr: Long, listener: RenderUpdateListener?): Boolean
private external fun nCommand(ptr: Long, command: Array<out String>): Boolean
private external fun nOption(ptr: Long, key: String, value: String): Boolean
private external fun nGetPropertyInt(ptr: Long, name: String): Int
private external fun nGetPropertyBoolean(ptr: Long, name: String): Boolean
private external fun nGetPropertyDouble(ptr: Long, name: String): Double
private external fun nGetPropertyString(ptr: Long, name: String): String?
private external fun nTryGetPropertyLong(ptr: Long, name: String, out: LongArray): Boolean
private external fun nTryGetPropertyDouble(ptr: Long, name: String, out: DoubleArray): Boolean
private external fun nTryGetPropertyBoolean(ptr: Long, name: String, out: BooleanArray): Boolean
private external fun nSetPropertyInt(ptr: Long, name: String, value: Int): Boolean
private external fun nSetPropertyBoolean(ptr: Long, name: String, value: Boolean): Boolean
private external fun nSetPropertyDouble(ptr: Long, name: String, value: Double): Boolean
private external fun nSetPropertyString(ptr: Long, name: String, value: String): Boolean
private external fun nObserveProperty(ptr: Long, name: String, format: Int, replyData: Long): Boolean
private external fun nUnobserveProperty(ptr: Long, replyData: Long): Boolean
private external fun nRegisterSeekableInput(ptr: Long, input: SeekableInput, uri: String, size: Long): Boolean
private external fun nUnregisterSeekableInput(ptr: Long, uri: String): Boolean

/**
 * Attach render surface to the mpv context.
 *
 * On Android, the surface should be `android.view.Surface` object.
 */
internal expect fun attachSurface(ptr: Long, surface: Any): Boolean

/**
 * Detach current render surface of the mpv context.
 */
internal expect fun detachSurface(ptr: Long): Boolean

private external fun nDestroy(ptr: Long): Boolean
private external fun nFinalize(ptr: Long)
