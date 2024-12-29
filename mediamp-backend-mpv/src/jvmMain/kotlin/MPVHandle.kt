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

class MPVHandle private constructor(private val nativePtr: Long) : AutoCloseable {
    private val cleanable = cleaner.register(this, ReferenceHolder(nativePtr))
    
    constructor(context: Any) : this(nMake(context)) {
        if (nativePtr == 0L) throw IllegalStateException("Failed to create native mpv handle")
    }
    
    fun initialize(): Boolean {
        try {
            return nInitialize(nativePtr)
        } finally {
            Reference.reachabilityFence(this)
        }
    }
    
    fun destroy(): Boolean {
        try {
            return nDestroy(nativePtr)
        } finally {
            Reference.reachabilityFence(this)
        }
    }

    override fun close() {
        cleanable.clean()
    }
    
    companion object {
        private val cleaner = Cleaner.create()
        
        private class ReferenceHolder(private val nativePtr: Long) : Runnable {
            override fun run() { nFinalize(nativePtr) }
        }
    }
}

private external fun nGlobalInit(): Boolean
private external fun nMake(context: Any): Long
private external fun nInitialize(ptr: Long): Boolean
private external fun nDestroy(ptr: Long): Boolean
private external fun nFinalize(ptr: Long)
