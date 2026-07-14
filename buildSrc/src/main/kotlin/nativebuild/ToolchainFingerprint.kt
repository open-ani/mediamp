/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package nativebuild

import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Captures the identity of the native toolchain (compiler/meson/system library versions)
 * as a build-cache key input. The native compile tasks read their tools from machine
 * state Gradle cannot fingerprint (MSYS2, Homebrew, apt, the NDK), so without this a
 * toolchain upgrade would silently keep hitting stale cache entries.
 *
 * Probes are best-effort: a missing tool yields a stable "unavailable" marker instead of
 * failing the build, so the fingerprint never breaks configurations that skip the
 * corresponding target.
 */
abstract class ToolchainFingerprintValueSource :
    ValueSource<String, ToolchainFingerprintValueSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        /** Each entry is one probe command line with arguments separated by [ARG_SEPARATOR]. */
        val probeCommandLines: ListProperty<String>
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String =
        parameters.probeCommandLines.get().joinToString("\n") { encoded ->
            val args = encoded.split(ARG_SEPARATOR)
            val display = args.joinToString(" ")
            runCatching {
                val output = ByteArrayOutputStream()
                execOperations.exec {
                    commandLine(args)
                    standardOutput = output
                    errorOutput = output
                    isIgnoreExitValue = true
                }
                "$display => ${output.toString(Charsets.UTF_8).trim()}"
            }.getOrElse { failure ->
                "$display => unavailable (${failure.message})"
            }
        }

    companion object {
        const val ARG_SEPARATOR: String = "\u0001"
    }
}

internal fun Project.toolchainFingerprint(probes: List<List<String>>): Provider<String> {
    val encoded = probes.map { it.joinToString(ToolchainFingerprintValueSource.ARG_SEPARATOR) }
    return providers.of(ToolchainFingerprintValueSource::class.java) {
        parameters.probeCommandLines.set(encoded)
    }
}
