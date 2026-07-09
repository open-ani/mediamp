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
    // private val cleanable = cleaner.register(this, ReferenceHolder(ptr))
    private var eventListener: EventListener? = null
    private var renderUpdateListener: RenderUpdateListener? = null
    // Atomic so close() can claim the pointer exactly once: concurrent close() calls
    // must not both reach nFinalize (that would double-delete the native instance).
    private val nativePtr = AtomicLong(ptr)

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

    fun getPropertyInt(name: String): Int {
        return nGetPropertyInt(ptr, name)
    }

    fun getPropertyBoolean(name: String): Boolean {
        return nGetPropertyBoolean(ptr, name)
    }

    fun getPropertyDouble(name: String): Double {
        return nGetPropertyDouble(ptr, name)
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
        return nObserveProperty(ptr, name, format.ordinal, replyData)
    }

    fun unobserveProperty(replyData: Long): Boolean {
        return nUnobserveProperty(ptr, replyData)
    }

    fun registerSeekableInput(input: SeekableInput, uri: String): String {
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

    /*companion object {
        init { LibraryLoader.loadLibraries() }
        
        private val cleaner = Cleaner.create()
        
        private class ReferenceHolder(private val nativePtr: Long) : Runnable {
            override fun run() {  }
        }
    }*/
}

@Suppress("unused")
enum class MPVFormat {
    MPV_FORMAT_NONE,
    MPV_FORMAT_STRING,
    MPV_FORMAT_OSD_STRING,
    MPV_FORMAT_FLAG,
    MPV_FORMAT_INT64,
    MPV_FORMAT_DOUBLE,
    MPV_FORMAT_NODE,
    MPV_FORMAT_NODE_ARRAY,
    MPV_FORMAT_NODE_MAP,
    MPV_FORMAT_BYTE_ARRAY,
}

private external fun nGlobalInit(): Boolean
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
