/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.nativeloader

import java.io.File
import java.net.URLClassLoader
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeRuntimeLoadingTest {

    private val libraryName = "testwrap"
    private val manifestName = "test-natives.txt"
    private val wrapperFileName = nativeLibraryFileName(libraryName)

    private val loadedDirs = mutableListOf<File>()
    private val loading = NativeRuntimeLoading { dir, name ->
        assertEquals(libraryName, name)
        loadedDirs.add(dir)
    }

    private fun tempDir(): File = createTempDirectory("native-loader-test").toFile().apply { deleteOnExit() }

    /** Builds a classpath directory with [manifest] and one resource file per [files] entry. */
    private fun runtimeOnClasspath(
        manifest: String?,
        files: Map<String, ByteArray> = emptyMap(),
    ): NativeClasspathRuntime {
        val resourceDir = tempDir()
        if (manifest != null) {
            resourceDir.resolve(manifestName).writeText(manifest)
        }
        files.forEach { (name, content) -> resourceDir.resolve(name).writeBytes(content) }
        // parent = null: resolution must not fall through to the test classpath
        val loader = URLClassLoader(arrayOf(resourceDir.toURI().toURL()), null)
        return NativeClasspathRuntime(libraryName, manifestName, loader)
    }

    @Test
    fun `extracts manifest files and loads wrapper`() {
        val wrapperBytes = byteArrayOf(1, 2, 3)
        val depBytes = byteArrayOf(9, 8, 7, 6)
        val runtime = runtimeOnClasspath(
            manifest = "$wrapperFileName\n\nlibdep.bin\n", // blank line must be skipped
            files = mapOf(wrapperFileName to wrapperBytes, "libdep.bin" to depBytes),
        )
        val target = tempDir()

        loading.setRuntimeDirectory(runtime, target, doExtract = true, validate = true)

        assertContentEquals(wrapperBytes, target.resolve(wrapperFileName).readBytes())
        assertContentEquals(depBytes, target.resolve("libdep.bin").readBytes())
        assertEquals(listOf(target.canonicalFile), loadedDirs)
        if (!isWindowsRuntime()) {
            assertTrue(target.resolve(wrapperFileName).canExecute(), "extracted libraries must be executable")
        }
    }

    @Test
    fun `reconfiguring the same directory is idempotent`() {
        val runtime = runtimeOnClasspath(manifest = wrapperFileName, files = mapOf(wrapperFileName to byteArrayOf(1)))
        val target = tempDir()

        loading.setRuntimeDirectory(runtime, target, doExtract = true, validate = true)
        loading.setRuntimeDirectory(runtime, target, doExtract = true, validate = true)

        assertEquals(1, loadedDirs.size, "wrapper must be loaded exactly once")
    }

    @Test
    fun `reconfiguring a different directory fails`() {
        val runtime = runtimeOnClasspath(manifest = wrapperFileName, files = mapOf(wrapperFileName to byteArrayOf(1)))
        loading.setRuntimeDirectory(runtime, tempDir(), doExtract = true, validate = true)

        val e = assertFailsWith<IllegalStateException> {
            loading.setRuntimeDirectory(runtime, tempDir(), doExtract = true, validate = true)
        }
        assertTrue("already loaded" in e.message.orEmpty(), "unexpected message: ${e.message}")
    }

    @Test
    fun `missing manifest reports actionable error`() {
        val runtime = runtimeOnClasspath(manifest = null)

        val e = assertFailsWith<IllegalStateException> {
            loading.setRuntimeDirectory(runtime, tempDir(), doExtract = true, validate = true)
        }
        assertTrue(manifestName in e.message.orEmpty(), "message should name the manifest: ${e.message}")
        assertTrue("runtime artifact" in e.message.orEmpty(), "message should hint at the artifact: ${e.message}")
    }

    @Test
    fun `manifest entry missing from classpath reports the file name`() {
        val runtime = runtimeOnClasspath(
            manifest = "$wrapperFileName\nlibmissing.bin",
            files = mapOf(wrapperFileName to byteArrayOf(1)),
        )

        val e = assertFailsWith<IllegalStateException> {
            loading.setRuntimeDirectory(runtime, tempDir(), doExtract = true, validate = true)
        }
        assertTrue("libmissing.bin" in e.message.orEmpty(), "unexpected message: ${e.message}")
    }

    @Test
    fun `validate short-circuits extraction when wrapper already present`() {
        // Loader has NO resources at all: any extraction attempt would throw.
        val runtime = runtimeOnClasspath(manifest = null)
        val target = tempDir()
        target.resolve(wrapperFileName).writeBytes(byteArrayOf(1))

        loading.setRuntimeDirectory(runtime, target, doExtract = true, validate = true)

        assertEquals(listOf(target.canonicalFile), loadedDirs)
    }

    @Test
    fun `no-extract mode delegates missing-wrapper detection to the platform loader`() {
        // Contract: with doExtract=false the loader itself does not re-validate; the platform
        // loadRuntimeWrapperLibrary throws "Failed to locate ..." for a missing wrapper.
        val runtime = runtimeOnClasspath(manifest = null)
        val target = tempDir()

        loading.setRuntimeDirectory(runtime, target, doExtract = false, validate = true)

        assertEquals(listOf(target.canonicalFile), loadedDirs, "load must still be attempted")
    }

    @Test
    fun `extraction overwrites stale files`() {
        val runtime = runtimeOnClasspath(manifest = wrapperFileName, files = mapOf(wrapperFileName to byteArrayOf(5, 5)))
        val target = tempDir()
        target.resolve(wrapperFileName).writeBytes(byteArrayOf(9, 9, 9, 9)) // stale
        // wrapper exists -> validate short-circuit would skip extraction; disable validate to force it
        loading.setRuntimeDirectory(runtime, target, doExtract = true, validate = false)

        assertContentEquals(byteArrayOf(5, 5), target.resolve(wrapperFileName).readBytes())
    }

    @Test
    fun `target path pointing at a file fails`() {
        val runtime = runtimeOnClasspath(manifest = wrapperFileName, files = mapOf(wrapperFileName to byteArrayOf(1)))
        val notADir = File(tempDir(), "occupied").apply { writeText("x") }

        assertFailsWith<IllegalArgumentException> {
            loading.setRuntimeDirectory(runtime, notADir, doExtract = true, validate = false)
        }
    }
}
