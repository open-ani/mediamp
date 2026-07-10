/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpvLibraryLoaderLogicTest {

    @Test
    fun `platform suffix covers every supported os-arch combination`() {
        val cases = mapOf(
            ("Mac OS X" to "aarch64") to "macos-arm64",
            ("Mac OS X" to "arm64") to "macos-arm64",
            ("Mac OS X" to "x86_64") to "macos-x64",
            ("Windows 11" to "amd64") to "windows-x64",
            ("Windows Server 2022" to "aarch64") to "windows-arm64",
            ("Linux" to "amd64") to "linux-x64",
            ("Linux" to "x86_64") to "linux-x64",
            ("Linux" to "aarch64") to "linux-arm64",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, platformSuffixFor(input.first, input.second), "for $input")
        }
    }

    @Test
    fun `unknown arch defaults to x64`() {
        assertEquals("linux-x64", platformSuffixFor("Linux", "riscv64"))
    }

    @Test
    fun `manifest selection prefers the platform-specific name`() {
        val selected = selectMpvManifestResource("macos-arm64") { name ->
            name == "mpv-natives-macos-arm64.txt" || name == MPV_LEGACY_MANIFEST
        }
        assertEquals("mpv-natives-macos-arm64.txt", selected)
    }

    @Test
    fun `manifest selection falls back to the legacy name`() {
        val selected = selectMpvManifestResource("macos-arm64") { it == MPV_LEGACY_MANIFEST }
        assertEquals(MPV_LEGACY_MANIFEST, selected)
    }

    @Test
    fun `manifest selection keeps platform name when nothing is on the classpath`() {
        val selected = selectMpvManifestResource("windows-x64") { false }
        assertEquals("mpv-natives-windows-x64.txt", selected)
    }

    @Test
    fun `missing runtime message tells the user exactly what to add`() {
        val message = mpvRuntimeMissingMessage("mpv-natives-macos-arm64.txt", "macos-arm64")
        assertTrue("mpv-natives-macos-arm64.txt" in message, "should name the missing manifest")
        assertTrue("org.openani.mediamp:mediamp-mpv-runtime\"" in message, "should suggest the aggregate artifact")
        assertTrue("org.openani.mediamp:mediamp-mpv-runtime-macos-arm64" in message, "should suggest the platform artifact")
        assertTrue("prepareLibraries" in message, "should mention the manual override")
    }
}
