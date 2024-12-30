/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.backend.mpv
import java.lang.ref.Cleaner
import java.lang.ref.Reference

class MPVHandle private constructor(private val ptr: Long) : AutoCloseable {
    private val cleanable = cleaner.register(this, ReferenceHolder(ptr))
    private var eventListener: EventListener? = null
    
    constructor(context: Any) : this(nMake(context)) {
        if (ptr == 0L) throw IllegalStateException("Failed to create native mpv handle")
    }
    
    fun initialize(): Boolean {
        try {
            return nInitialize(ptr)
        } finally {
            Reference.reachabilityFence(this)
        }
    }
    
    fun setEventListener(listener: EventListener) {
        eventListener = listener
        nSetEventListener(ptr, listener)
    }
    
    fun command(vararg command: String): Boolean {
        try {
            return nCommand(ptr, command)
        } finally {
            Reference.reachabilityFence(this)
        }
    }
    
    fun option(key: String, value: String): Boolean {
        try {
            return nOption(ptr, key, value)
        } finally {
            Reference.reachabilityFence(this)
        }
    }
    
    fun getPropertyInt(name: String): Int {
        try {
            return nGetPropertyInt(ptr, name)
        } finally {
            Reference.reachabilityFence(this)
        }
    }
    
    fun getPropertyBoolean(name: String): Boolean {
        try {
            return nGetPropertyBoolean(ptr, name)
        } finally {
            Reference.reachabilityFence(this)
        }
    }
    
    fun getPropertyDouble(name: String): Double {
        try {
            return nGetPropertyDouble(ptr, name)
        } finally {
            Reference.reachabilityFence(this)
        }
    }
    
    fun getPropertyString(name: String): String {
        try {
            return nGetPropertyString(ptr, name)
        } finally {
            Reference.reachabilityFence(this)
        }
    }
    
    fun setPropertyInt(name: String, value: Int): Boolean {
        try {
            return nSetPropertyInt(ptr, name, value)
        } finally {
            Reference.reachabilityFence(this)
        }
    }
    
    fun setPropertyBoolean(name: String, value: Boolean): Boolean {
        try {
            return nSetPropertyBoolean(ptr, name, value)
        } finally {
            Reference.reachabilityFence(this)
        }
    }
    
    fun setPropertyDouble(name: String, value: Double): Boolean {
        try {
            return nSetPropertyDouble(ptr, name, value)
        } finally {
            Reference.reachabilityFence(this)
        }
    }
    
    fun setPropertyString(name: String, value: String): Boolean {
        try {
            return nSetPropertyString(ptr, name, value)
        } finally {
            Reference.reachabilityFence(this)
        }
    }
    
    fun observeProperty(name: String, format: MPVFormat, replyData: Long = 0L): Boolean {
        try {
            return nObserveProperty(ptr, name, format.ordinal, replyData)
        } finally {
            Reference.reachabilityFence(this)
        }
    }
    
    fun unobserveProperty(replyData: Long): Boolean {
        try {
            return nUnobserveProperty(ptr, replyData)
        } finally {
            Reference.reachabilityFence(this)
        }
    }
    
    fun attachAndroidSurface(surface: Any): Boolean {
        try {
            return nAttachAndroidSurface(ptr, surface)
        } finally {
            Reference.reachabilityFence(this)
        }
    }
    
    fun detachAndroidSurface(): Boolean {
        try {
            return nDetachAndroidSurface(ptr)
        } finally {
            Reference.reachabilityFence(this)
        }
    }
    
    fun destroy(): Boolean {
        try {
            return nDestroy(ptr)
        } finally {
            Reference.reachabilityFence(this)
        }
    }

    override fun close() {
        cleanable.clean()
    }
    
    companion object {
        init { LibraryLoader.loadLibraries() }
        
        private val cleaner = Cleaner.create()
        
        private class ReferenceHolder(private val nativePtr: Long) : Runnable {
            override fun run() { nFinalize(nativePtr) }
        }
    }
}

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
private external fun nCommand(ptr: Long, command: Array<out String>): Boolean
private external fun nOption(ptr: Long, key: String, value: String): Boolean
private external fun nGetPropertyInt(ptr: Long, name: String): Int
private external fun nGetPropertyBoolean(ptr: Long, name: String): Boolean
private external fun nGetPropertyDouble(ptr: Long, name: String): Double
private external fun nGetPropertyString(ptr: Long, name: String): String
private external fun nSetPropertyInt(ptr: Long, name: String, value: Int): Boolean
private external fun nSetPropertyBoolean(ptr: Long, name: String, value: Boolean): Boolean
private external fun nSetPropertyDouble(ptr: Long, name: String, value: Double): Boolean
private external fun nSetPropertyString(ptr: Long, name: String, value: String): Boolean
private external fun nObserveProperty(ptr: Long, name: String, format: Int, replyData: Long): Boolean
private external fun nUnobserveProperty(ptr: Long, replyData: Long): Boolean

private external fun nAttachAndroidSurface(ptr: Long, surface: Any): Boolean
private external fun nDetachAndroidSurface(ptr: Long): Boolean

private external fun nDestroy(ptr: Long): Boolean
private external fun nFinalize(ptr: Long)
