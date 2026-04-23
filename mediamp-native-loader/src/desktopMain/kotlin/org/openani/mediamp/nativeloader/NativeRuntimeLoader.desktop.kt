/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.nativeloader

import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.File

@Suppress("UnsafeDynamicallyLoadedCode")
internal actual fun loadRuntimeWrapperLibrary(runtimeDir: File, wrapperName: String) {
    val wrapperFile = runtimeDir.resolve(nativeLibraryFileName(wrapperName))

    if (!wrapperFile.isFile) {
        error("Failed to locate ${wrapperFile.name} in runtime directory ${runtimeDir.absolutePath}")
    }

    if (isWindowsRuntime()) {
        withWindowsDllDirectory(runtimeDir) {
            System.load(wrapperFile.absolutePath)
        }
    } else {
        System.load(wrapperFile.absolutePath)
    }
}

private inline fun <T> withWindowsDllDirectory(runtimeDir: File, block: () -> T): T {
    val configured = WindowsDllKernel32.INSTANCE.SetDllDirectoryW(WString(runtimeDir.absolutePath))
    check(configured) {
        "Failed to add Windows DLL search directory: ${runtimeDir.absolutePath}"
    }
    try {
        return block()
    } finally {
        WindowsDllKernel32.INSTANCE.SetDllDirectoryW(null)
    }
}

@Suppress("FunctionName")
private interface WindowsDllKernel32 : StdCallLibrary {
    fun SetDllDirectoryW(pathName: WString?): Boolean

    companion object {
        val INSTANCE: WindowsDllKernel32 = Native.load(
            "kernel32",
            WindowsDllKernel32::class.java,
            W32APIOptions.DEFAULT_OPTIONS,
        ) as WindowsDllKernel32
    }
}
